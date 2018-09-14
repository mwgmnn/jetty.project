//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security;

import java.io.Serializable;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.security.authentication.AuthorizationService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

public class SpnegoLoginService2 extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(SpnegoLoginService2.class);

    private final GSSManager _gssManager = GSSManager.getInstance();
    private final String _realm;
    private final AuthorizationService _authorizationService;
    private IdentityService _identityService = new DefaultIdentityService();
    private String _serviceName;
    private Path _keyTabPath;
    private String _hostName;
    private SpnegoContext _context;

    public SpnegoLoginService2(String realm, AuthorizationService authorizationService)
    {
        _realm = realm;
        _authorizationService = authorizationService;
    }

    @Override
    public String getName()
    {
        return _realm;
    }

    public Path getKeyTabPath()
    {
        return _keyTabPath;
    }

    public void setKeyTabPath(Path keyTabFile)
    {
        _keyTabPath = keyTabFile;
    }

    public String getServiceName()
    {
        return _serviceName;
    }

    public void setServiceName(String serviceName)
    {
        _serviceName = serviceName;
    }

    public String getHostName()
    {
        return _hostName;
    }

    public void setHostName(String hostName)
    {
        _hostName = hostName;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_hostName == null)
            _hostName = InetAddress.getLocalHost().getCanonicalHostName();
        if (LOG.isDebugEnabled())
            LOG.debug("Retrieving credentials for service {}/{}", getServiceName(), getHostName());
        LoginContext loginContext = new LoginContext("", null, null, new SpnegoConfiguration());
        loginContext.login();
        Subject subject = loginContext.getSubject();
        _context = Subject.doAs(subject, newSpnegoContext(subject));
        super.doStart();
    }

    private PrivilegedAction<SpnegoContext> newSpnegoContext(Subject subject)
    {
        return () ->
        {
            try
            {
                GSSName serviceName = _gssManager.createName(getServiceName() + "@" + getHostName(), GSSName.NT_HOSTBASED_SERVICE);
                Oid kerberosOid = new Oid("1.2.840.113554.1.2.2");
                Oid spnegoOid = new Oid("1.3.6.1.5.5.2");
                Oid[] mechanisms = new Oid[]{kerberosOid, spnegoOid};
                GSSCredential serviceCredential = _gssManager.createCredential(serviceName, GSSCredential.DEFAULT_LIFETIME, mechanisms, GSSCredential.ACCEPT_ONLY);
                SpnegoContext context = new SpnegoContext();
                context._subject = subject;
                context._serviceCredential = serviceCredential;
                return context;
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest req)
    {
        Subject subject = _context._subject;
        HttpServletRequest request = (HttpServletRequest)req;
        HttpSession httpSession = request.getSession(false);
        GSSContext gssContext = null;
        if (httpSession != null)
        {
            GSSContextHolder holder = (GSSContextHolder)httpSession.getAttribute(GSSContextHolder.ATTRIBUTE);
            gssContext = holder == null ? null : holder.gssContext;
        }
        if (gssContext == null)
            gssContext = Subject.doAs(subject, newGSSContext());

        byte[] input = Base64.getDecoder().decode((String)credentials);
        byte[] output = Subject.doAs(_context._subject, acceptGSSContext(gssContext, input));
        String token = Base64.getEncoder().encodeToString(output);

        String userName = toUserName(gssContext);
        // Save the token in the principal so it can be sent in the response.
        SpnegoUserPrincipal principal = new SpnegoUserPrincipal(userName, token);
        if (gssContext.isEstablished())
        {
            if (httpSession != null)
                httpSession.removeAttribute(GSSContextHolder.ATTRIBUTE);

            UserIdentity roles = _authorizationService.getUserIdentity(request, userName, "");
            return new SpnegoUserIdentity(subject, principal, roles);
        }
        else
        {
            // The GSS context is not established yet, save it into the HTTP session.
            if (httpSession == null)
                httpSession = request.getSession(true);
            GSSContextHolder holder = new GSSContextHolder(gssContext);
            httpSession.setAttribute(GSSContextHolder.ATTRIBUTE, holder);

            // Return an unestablished UserIdentity.
            return new SpnegoUserIdentity(subject, principal, null);
        }
    }

    private PrivilegedAction<GSSContext> newGSSContext()
    {
        return () ->
        {
            try
            {
                return _gssManager.createContext(_context._serviceCredential);
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    private PrivilegedAction<byte[]> acceptGSSContext(GSSContext gssContext, byte[] token)
    {
        return () ->
        {
            try
            {
                return gssContext.acceptSecContext(token, 0, token.length);
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    private String toUserName(GSSContext gssContext)
    {
        try
        {
            String name = gssContext.getSrcName().toString();
            int at = name.indexOf('@');
            if (at < 0)
                return name;
            return name.substring(0, at);
        }
        catch (GSSException x)
        {
            throw new RuntimeException(x);
        }
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        return false;
    }

    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    @Override
    public void setIdentityService(IdentityService identityService)
    {
        _identityService = identityService;
    }

    @Override
    public void logout(UserIdentity user)
    {
    }

    private class SpnegoConfiguration extends Configuration
    {
        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name)
        {
            String principal = getServiceName() + "/" + getHostName();
            Map<String, Object> options = new HashMap<>();
            if (LOG.isDebugEnabled())
                options.put("debug", "true");
            options.put("doNotPrompt", "true");
            options.put("principal", principal);
            options.put("useKeyTab", "true");
            Path keyTabPath = getKeyTabPath();
            if (keyTabPath != null)
                options.put("keyTab", keyTabPath.toAbsolutePath().toString());
            // This option is required to store the service credentials in
            // the Subject, so that it can be later used by acceptSecContext().
            options.put("storeKey", "true");
            options.put("isInitiator", "false");
            String moduleClass = "com.sun.security.auth.module.Krb5LoginModule";
            AppConfigurationEntry config = new AppConfigurationEntry(moduleClass, AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options);
            return new AppConfigurationEntry[]{config};
        }
    }

    private static class SpnegoContext
    {
        private Subject _subject;
        private GSSCredential _serviceCredential;
    }

    private static class GSSContextHolder implements Serializable
    {
        public static final String ATTRIBUTE = GSSContextHolder.class.getName();

        private transient final GSSContext gssContext;

        private GSSContextHolder(GSSContext gssContext)
        {
            this.gssContext = gssContext;
        }
    }
}
