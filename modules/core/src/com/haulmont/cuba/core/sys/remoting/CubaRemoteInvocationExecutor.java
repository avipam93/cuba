/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.haulmont.cuba.core.sys.remoting;

import com.haulmont.cuba.core.app.ServerConfig;
import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.Configuration;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.SecurityContext;
import com.haulmont.cuba.security.app.LoginService;
import com.haulmont.cuba.security.global.UserSession;
import com.haulmont.cuba.security.sys.UserSessionManager;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationExecutor;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

/**
 * Executes {@link CubaRemoteInvocation} on middleware, setting and clearing a {@link SecurityContext} in the request
 * handling thread.
 *
 */
public class CubaRemoteInvocationExecutor implements RemoteInvocationExecutor {

    private Logger log = LoggerFactory.getLogger(CubaRemoteInvocationExecutor.class);

    private UserSessionManager userSessionManager;

    private volatile ClusterInvocationSupport clusterInvocationSupport;

    private Configuration configuration;

    public CubaRemoteInvocationExecutor() {
        userSessionManager = AppBeans.get("cuba_UserSessionManager");
        configuration = AppBeans.get(Configuration.NAME);
    }

    @Override
    public Object invoke(RemoteInvocation invocation, Object targetObject)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (invocation instanceof CubaRemoteInvocation) {
            UUID sessionId = ((CubaRemoteInvocation) invocation).getSessionId();
            if (sessionId != null) {
                UserSession session = userSessionManager.findSession(sessionId);
                if (session == null) {
                    String sessionProviderUrl = configuration.getConfig(ServerConfig.class).getUserSessionProviderUrl();
                    if (StringUtils.isNotBlank(sessionProviderUrl)) {
                        log.debug("User session " + sessionId + " not found, trying to get it from " + sessionProviderUrl);
                        try {
                            HttpServiceProxy proxyFactory = new HttpServiceProxy(
                                    getClusterInvocationSupport(sessionProviderUrl));
                            proxyFactory.setServiceUrl("cuba_LoginService");
                            proxyFactory.setServiceInterface(LoginService.class);
                            proxyFactory.afterPropertiesSet();
                            LoginService loginService = (LoginService) proxyFactory.getObject();
                            if (loginService != null) {
                                UserSession userSession = loginService.getSession(sessionId);
                                if (userSession != null) {
                                    userSessionManager.storeSession(userSession);
                                } else {
                                    log.debug("User session " + sessionId + " not found on " + sessionProviderUrl);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error getting user session from " + sessionProviderUrl, e);
                        }
                    }
                }
                AppContext.setSecurityContext(new SecurityContext(sessionId));
            }
        }
        Object result = invocation.invoke(targetObject);
        AppContext.setSecurityContext(null);
        return result;
    }

    private ClusterInvocationSupport getClusterInvocationSupport(String sessionProviderUrl) {
        if (clusterInvocationSupport == null) {
            synchronized (this) {
                if (clusterInvocationSupport == null) {
                    ClusterInvocationSupport result = new ClusterInvocationSupport();
                    result.setBaseUrl(sessionProviderUrl);
                    result.init();
                    clusterInvocationSupport = result;
                }
            }
        }
        return clusterInvocationSupport;
    }
}