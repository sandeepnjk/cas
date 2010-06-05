/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.server;

import com.github.inspektr.audit.annotation.Audit;
import org.jasig.cas.authentication.principal.*;
import org.jasig.cas.server.AuthenticationResponsePlugin;
import org.jasig.cas.server.CentralAuthenticationService;
import org.jasig.cas.server.PreAuthenticationPlugin;
import org.jasig.cas.server.authentication.*;
import org.jasig.cas.server.login.*;
import org.jasig.cas.server.logout.DefaultLogoutResponseImpl;
import org.jasig.cas.server.logout.LogoutRequest;
import org.jasig.cas.server.logout.LogoutResponse;
import org.jasig.cas.server.session.*;
import org.jasig.cas.services.RegisteredService;
import org.jasig.cas.services.ServicesManager;
import org.jasig.cas.services.UnauthorizedProxyingException;
import org.jasig.cas.services.UnauthorizedServiceException;
import org.jasig.cas.services.UnauthorizedSsoServiceException;
import org.jasig.cas.ticket.InvalidTicketException;
import org.jasig.cas.ticket.TicketCreationException;
import org.jasig.cas.ticket.TicketException;
import org.jasig.cas.server.session.Assertion;
import org.perf4j.aop.Profiled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.validation.constraints.NotNull;
import java.io.Writer;
import java.util.*;

/**
 * Concrete implementation of a CentralAuthenticationService, and also the
 * central, organizing component of CAS's internal implementation.
 * <p>
 * This class is thread safe.
 * <p>
 * This class has the following properties that must be set:
 * <ul>
 * <li> <code>ticketRegistry</code> - The Ticket Registry to maintain the list
 * of available tickets.</li>
 * <li> <code>serviceTicketRegistry</code> - Provides an alternative to configure separate registries for TGTs and ST in order to store them
 * in different locations (i.e. long term memory or short-term)</li>
 * <li> <code>authenticationManager</code> - The service that will handle
 * authentication.</li>
 * <li> <code>ticketGrantingTicketUniqueTicketIdGenerator</code> - Plug in to
 * generate unique secure ids for TicketGrantingTickets.</li>
 * <li> <code>serviceTicketUniqueTicketIdGenerator</code> - Plug in to
 * generate unique secure ids for ServiceTickets.</li>
 * <li> <code>ticketGrantingTicketExpirationPolicy</code> - The expiration
 * policy for TicketGrantingTickets.</li>
 * <li> <code>serviceTicketExpirationPolicy</code> - The expiration policy for
 * ServiceTickets.</li>
 * </ul>
 * 
 * @author William G. Thompson, Jr.
 * @author Scott Battaglia
 * @author Dmitry Kopylenko
 * @version $Revision: 1.16 $ $Date: 2007/04/24 18:11:36 $
 * @since 3.0
 */
public final class DefaultCentralAuthenticationServiceImpl implements CentralAuthenticationService {

    /** Log instance for logging events, info, warnings, errors, etc. */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * AuthenticationManager for authenticating credentials for purposes of
     * obtaining tickets.
     */
    @NotNull
    private final AuthenticationManager authenticationManager;

    @NotNull
    private final SessionStorage sessionStorage;

    /** Implementation of Service Manager */
    @NotNull
    private ServicesManager servicesManager;

    /** Encoder to generate PseudoIds. */
    @NotNull
    private PersistentIdGenerator persistentIdGenerator = new ShibbolethCompatiblePersistentIdGenerator();

    @NotNull
    private List<PreAuthenticationPlugin> preAuthenticationPlugins = new ArrayList<PreAuthenticationPlugin>();

    @NotNull
    private List<AuthenticationResponsePlugin> authenticationResponsePlugins = new ArrayList<AuthenticationResponsePlugin>();

    public DefaultCentralAuthenticationServiceImpl(final AuthenticationManager authenticationManager, final SessionStorage sessionStorage) {
        this.authenticationManager = authenticationManager;
        this.sessionStorage = sessionStorage;
    }

    @Audit(action="CREATE_SESSION", actionResolverName="CREATE_SESSION_RESOLVER", resourceResolverName="CREATE_SESSION_RESOURCE_RESOLVER")
    @Profiled(tag = "CREATE_SESSION", logFailuresSeparately = false)
    public LoginResponse login(final LoginRequest loginRequest) {
        Assert.notNull(loginRequest, "loginRequest cannot be null.");
        final AuthenticationRequest authenticationRequest = new DefaultAuthenticationRequestImpl(loginRequest.getCredentials(), loginRequest.isLongTermLoginRequest());

        for (final PreAuthenticationPlugin plugin : this.preAuthenticationPlugins) {
            final LoginResponse loginResponse = plugin.continueWithAuthentication(loginRequest);

            if (loginResponse != null) {
                return loginResponse;
            }
        }

        final AuthenticationResponse authenticationResponse = this.authenticationManager.authenticate(authenticationRequest);

        for (final AuthenticationResponsePlugin authenticationResponsePlugin : this.authenticationResponsePlugins) {
            authenticationResponsePlugin.handle(loginRequest, authenticationResponse);
        }

        if (authenticationResponse.succeeded()) {
            final Session session = this.sessionStorage.createSession(authenticationResponse);
            return new DefaultLoginResponseImpl(session.getId(), authenticationResponse);
        }

        return new DefaultLoginResponseImpl(authenticationResponse);
    }


    /**
     * Note, we only currently support this is on the top, user-initiated session.
     */
    @Audit(action="DESTROY_SESSION",actionResolverName="DESTROY_SESSION_RESOLVER",resourceResolverName="DESTROY_SESSION_RESOURCE_RESOLVER")
    @Profiled(tag = "DESTROY_SESSION",logFailuresSeparately = false)
    public LogoutResponse logout(final LogoutRequest logoutRequest) {
        final Session session = this.sessionStorage.destroySession(logoutRequest.getSessionId());

        if (session != null) {
            session.invalidate();
            return new DefaultLogoutResponseImpl(session);
        }

        return new DefaultLogoutResponseImpl();
    }

    @Audit(action="ADMIN_DESTROY_SESSIONS",actionResolverName="ADMIN_DESTROY_SESSIONS_RESOLVER",resourceResolverName="ADMIN_DESTROY_SESSIONS_RESOURCE_RESOLVER")
    @Profiled(tag = "ADMIN_DESTROY_SESSION",logFailuresSeparately = false)
    public LogoutResponse logout(final String userId) {
        Assert.notNull(userId, "userId cannot be null");
        final Set<Session> sessions = this.sessionStorage.findSessionsByPrincipal(userId);

        if (sessions.isEmpty()) {
            return new DefaultLogoutResponseImpl();
        }

        final Set<Session> destroyedSessions = new HashSet<Session>();

        for (final Session session : sessions) {
            final Session destroyedSession = this.sessionStorage.destroySession(session.getId());
            destroyedSessions.add(destroyedSession);
        }

        return new DefaultLogoutResponseImpl(destroyedSessions);
    }

    /**
     * @throws IllegalArgumentException if TicketGrantingTicket ID, Credentials
     * or Service are null.
     */
    @Audit(action="SERVICE_TICKET",actionResolverName="GRANT_SERVICE_TICKET_RESOLVER",resourceResolverName="GRANT_SERVICE_TICKET_RESOURCE_RESOLVER")
    @Profiled(tag="GRANT_SERVICE_TICKET", logFailuresSeparately = false)
    @Transactional(readOnly = false)
    public String grantServiceTicket(final String ticketGrantingTicketId, final Service service, final Credential credentials) throws TicketException {
        Assert.notNull(ticketGrantingTicketId, "ticketGrantingticketId cannot be null");
        Assert.notNull(service, "service cannot be null");

        final Session session = this.sessionStorage.findSessionBySessionId(ticketGrantingTicketId);

        if (session == null) {
            throw new InvalidTicketException();
        }

        final RegisteredService registeredService = this.servicesManager.findServiceBy(service);

        if (registeredService == null || !registeredService.isEnabled()) {
            log.warn("ServiceManagement: Unauthorized Service Access. Service [" + service.getId() + "] not found in Service Registry.");
            throw new UnauthorizedServiceException();
        }

        if (!registeredService.isSsoEnabled() && credentials == null && !session.hasNotBeenUsed()) {
            log.warn("ServiceManagement: Service Not Allowed to use SSO.  Service [" + service.getId() + "]");
            throw new UnauthorizedSsoServiceException();
        }

        if (credentials != null) {
                final AuthenticationRequest authenticationRequest = new DefaultAuthenticationRequestImpl(Arrays.asList(credentials), false);
                final AuthenticationResponse authenticationResponse = this.authenticationManager.authenticate(authenticationRequest);

                if (!authenticationResponse.succeeded()) {
                    throw new TicketCreationException(); // TODO we'll want to actually grab the right exceptions
                }

            // TODO is this correct?
                if (!(authenticationResponse.getPrincipal().equals(session.getPrincipal()))) {
                    throw new TicketCreationException();
                }
        }

        // TODO replace this once we get everything in place
        final ServiceAccessRequest serviceAccessRequest = new ServiceAccessRequest() {
            public String getServiceId() {
                return service.getId();
            }

            public String getPassiveAuthenticationRedirectUrl() {
                return service.getId();
            }

            public List<Credential> getCredentials() {
                return Collections.emptyList();
            }

            public Date getDate() {
                return new Date();
            }

            public boolean isForceAuthentication() {
                return credentials != null;
            }

            public String getRemoteIpAddress() {
                return "";
            }

            public String getSessionId() {
                return null;
            }

            public void setSessionId(String sessionId) {

            }

            public boolean isPassiveAuthentication() {
                return false;
            }

            public boolean isLongTermLoginRequest() {
                return false;
            }

            public Access getOriginalAccess() {
                return null;
            }
        };

        try {
            final Access access = session.grant(serviceAccessRequest);
            this.sessionStorage.updateSession(session);
            return access.getId();
        } catch (final InvalidatedSessionException e) {
            throw new InvalidTicketException(e);
        }
    }

    @Audit(action="SERVICE_TICKET", actionResolverName="GRANT_SERVICE_TICKET_RESOLVER", resourceResolverName="GRANT_SERVICE_TICKET_RESOURCE_RESOLVER")
    @Profiled(tag = "GRANT_SERVICE_TICKET",logFailuresSeparately = false)
    @Transactional(readOnly = false)
    public String grantServiceTicket(final String ticketGrantingTicketId, final Service service) {
        return this.grantServiceTicket(ticketGrantingTicketId, service, null);
    }

    /**
     * @throws IllegalArgumentException if the ServiceTicketId or the
     * Credentials are null.
     */
    @Audit(action="PROXY_GRANTING_TICKET",actionResolverName="GRANT_PROXY_GRANTING_TICKET_RESOLVER",resourceResolverName="GRANT_PROXY_GRANTING_TICKET_RESOURCE_RESOLVER")
    @Profiled(tag="GRANT_PROXY_GRANTING_TICKET",logFailuresSeparately = false)
    @Transactional(readOnly = false)
    public String delegateTicketGrantingTicket(final String serviceTicketId, final Credential credential) {

        Assert.notNull(serviceTicketId, "serviceTicketId cannot be null");
        Assert.notNull(credential, "credentials cannot be null");

        final AuthenticationRequest authenticationRequest = new DefaultAuthenticationRequestImpl(Arrays.asList(credential), false);
        final AuthenticationResponse authenticationResponse = this.authenticationManager.authenticate(authenticationRequest);

        final Session session = this.sessionStorage.findSessionByAccessId(serviceTicketId);

        if (session == null) {
            throw new InvalidTicketException();
        }

        final Access access = session.getAccess(serviceTicketId);

        // TODO we should be doing more a check than this.  Not sure why I didn't have that on the interface
        if (access == null) {
            throw new InvalidTicketException();
        }

        final RegisteredService registeredService = this.servicesManager.findServiceBy(new Service() {
            public void setPrincipal(AttributePrincipal principal) {
            }

            public boolean logOutOfService(String sessionIdentifier) {
                return false;
            }

            public boolean matches(Service service) {
                return true;
            }

            public String getId() {
                return access.getResourceIdentifier();
            }

            public Map<String, Object> getAttributes() {
                return Collections.emptyMap();
            }
        });

        if (registeredService == null || !registeredService.isEnabled()
            || !registeredService.isAllowedToProxy()) {
            log.warn("ServiceManagement: Service Attempted to Proxy, but is not allowed.  Service: [" + access.getResourceIdentifier() + "]");
            throw new UnauthorizedProxyingException();
        }

        try {
            final Session delegatedSession = session.createDelegatedSession(authenticationResponse);
            // TODO not sure if this will work
            this.sessionStorage.updateSession(session);
            return this.sessionStorage.updateSession(delegatedSession).getId();

        } catch (final InvalidatedSessionException e) {
            throw new InvalidTicketException(e);
        }
    }

    /**
     * @throws IllegalArgumentException if the ServiceTicketId or the Service
     * are null.
     */
    @Audit(action="SERVICE_TICKET_VALIDATE",actionResolverName="VALIDATE_SERVICE_TICKET_RESOLVER",resourceResolverName="VALIDATE_SERVICE_TICKET_RESOURCE_RESOLVER")
    @Profiled(tag="VALIDATE_SERVICE_TICKET",logFailuresSeparately = false)
    @Transactional(readOnly = false)
    public Assertion validateServiceTicket(final String serviceTicketId, final Service service) {
        Assert.notNull(serviceTicketId, "serviceTicketId cannot be null");
        Assert.notNull(service, "service cannot be null");

        final Session session = this.sessionStorage.findSessionByAccessId(serviceTicketId);

        if (session == null) {
            log.info("ServiceTicket [" + serviceTicketId + "] does not exist.");
            throw new InvalidTicketException();
        }
        final Access access = session.getAccess(serviceTicketId);
        final RegisteredService registeredService = this.servicesManager.findServiceBy(service);

        if (registeredService == null || !registeredService.isEnabled()) {
            log.warn("ServiceManagement: Service does not exist is not enabled, and thus not allowed to validate tickets.   Service: [" + service.getId() + "]");
            throw new UnauthorizedServiceException("Service not allowed to validate tickets.");
        }

        access.validate(new TokenServiceAccessRequest() {
            public String getToken() {
                return serviceTicketId;
            }

            public String getServiceId() {
                return service.getId();
            }

            public String getPassiveAuthenticationRedirectUrl() {
                return service.getId();
            }

            public List<Credential> getCredentials() {
                return null;
            }

            public Date getDate() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public boolean isForceAuthentication() {
                return false;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getRemoteIpAddress() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getSessionId() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setSessionId(String sessionId) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public boolean isPassiveAuthentication() {
                return false;
            }

            public boolean isLongTermLoginRequest() {
                return false;
            }

            public Access getOriginalAccess() {
                return null;
            }
        });

        final AccessResponseResult accessResponseResult = access.generateResponse(new AccessResponseRequest() {
            public Writer getWriter() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getProxySessionId() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public Credential getProxiedCredential() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }
        });

        // TODO BROKEN FOR THE MOMENT
        return null;

        /*
        try {
            synchronized (access) {
                if (serviceTicket.isExpired()) {
                    log.info("ServiceTicket [" + serviceTicketId + "] has expired.");
                    throw new InvalidTicketException();
                }

                if (!serviceTicket.isValidFor(service)) {
                    log.error("ServiceTicket [" + serviceTicketId + "] with service [" + serviceTicket.getService().getId() + " does not match supplied service [" + service + "]");
                    throw new TicketValidationException(serviceTicket.getService());
                }
            }

            final Authentication authentication = session.getRootAuthentication();
            final AttributePrincipal principal = authentication.getPrincipal();
            final String principalId = registeredService.isAnonymousAccess() ? this.persistentIdGenerator.generate(principal, access.getResourceIdentifier()) : principal.getName();
                
            final Authentication authToUse;
            
            if (!registeredService.isIgnoreAttributes()) {
                final Map<String, Object> attributes = new HashMap<String, Object>();
    
                for (final String attribute : registeredService.getAllowedAttributes()) {
                    final Object value = principal.getAttributes().get(attribute);
    
                    if (value != null) {
                        attributes.put(attribute, value);
                    }
                }

                final AttributePrincipal modifiedPrincipal = new AttributePrincipal() {

                    public List<Object> getAttributeValues(String attribute) {
                        return null;  //To change body of implemented methods use File | Settings | File Templates.
                    }

                    public Object getAttributeValue(String attribute) {
                        return null;  //To change body of implemented methods use File | Settings | File Templates.
                    }

                    public Map<String, List<Object>> getAttributes() {
                        return null;  //To change body of implemented methods use File | Settings | File Templates.
                    }

                    public String getName() {
                        return null;  //To change body of implemented methods use File | Settings | File Templates.
                    }
                };

                final Authentication authentication =
                final MutableAuthentication mutableAuthentication = new MutableAuthentication(
                    modifiedPrincipal, authentication.getAuthenticatedDate());
                mutableAuthentication.getAttributes().putAll(
                    authentication.getAttributes());
                mutableAuthentication.getAuthenticatedDate().setTime(
                    authentication.getAuthenticatedDate().getTime());
                authToUse = mutableAuthentication;
            } else {
                authToUse = authentication;
            }
            

            final List<Authentication> authentications = new ArrayList<Authentication>();

            for (int i = 0; i < authenticationChainSize - 1; i++) {
                authentications.add(session.getProxiedAuthentications().get(i));
            }
            authentications.add(authToUse);

            return new ImmutableAssertionImpl(authentications, serviceTicket.getService(), serviceTicket.isFromNewLogin());
        } finally {
            if (serviceTicket.isExpired()) {
                this.serviceTicketRegistry.deleteTicket(serviceTicketId);
            }
        }
        */
    }

    public void setServicesManager(final ServicesManager servicesManager) {
        this.servicesManager = servicesManager;
    }

    public void setPersistentIdGenerator(
        final PersistentIdGenerator persistentIdGenerator) {
        this.persistentIdGenerator = persistentIdGenerator;
    }

    public void setPreAuthenticationPlugins(final List<PreAuthenticationPlugin> preAuthenticationPlugins) {
        this.preAuthenticationPlugins = preAuthenticationPlugins;
    }

    public void setAuthenticationResponsePlugins(final List<AuthenticationResponsePlugin> authenticationResponsePlugins) {
        this.authenticationResponsePlugins = authenticationResponsePlugins;
    }
}
