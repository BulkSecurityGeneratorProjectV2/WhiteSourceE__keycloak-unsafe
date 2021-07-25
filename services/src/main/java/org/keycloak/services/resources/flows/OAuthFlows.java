/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.services.resources.flows;

import org.jboss.logging.Logger;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.ClientConnection;
import org.keycloak.OAuth2Constants;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredCredentialModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserModel.RequiredAction;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.services.managers.AccessCode;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.TokenManager;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class OAuthFlows {

    private static final Logger log = Logger.getLogger(OAuthFlows.class);

    private final KeycloakSession session;

    private final RealmModel realm;

    private final HttpRequest request;

    private final UriInfo uriInfo;

    private ClientConnection clientConnection;
    private final AuthenticationManager authManager;

    private final TokenManager tokenManager;

    OAuthFlows(KeycloakSession session, RealmModel realm, HttpRequest request, UriInfo uriInfo, ClientConnection clientConnection, AuthenticationManager authManager,
            TokenManager tokenManager) {
        this.session = session;
        this.realm = realm;
        this.request = request;
        this.uriInfo = uriInfo;
        this.clientConnection = clientConnection;
        this.authManager = authManager;
        this.tokenManager = tokenManager;
    }

    public Response redirectAccessCode(AccessCode accessCode, UserSessionModel userSession, String state, String redirect) {
        String code = accessCode.getCode();
        UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam(OAuth2Constants.CODE, code);
        log.debugv("redirectAccessCode: state: {0}", state);
        if (state != null)
            redirectUri.queryParam(OAuth2Constants.STATE, state);
        Response.ResponseBuilder location = Response.status(302).location(redirectUri.build());
        Cookie remember = request.getHttpHeaders().getCookies().get(AuthenticationManager.KEYCLOAK_REMEMBER_ME);

        Cookie sessionCookie = request.getHttpHeaders().getCookies().get(AuthenticationManager.KEYCLOAK_SESSION_COOKIE);
        if (sessionCookie != null) {

            String[] split = sessionCookie.getValue().split("/");
            if (split.length >= 3) {
                String oldSessionId = split[2];
                if (!oldSessionId.equals(userSession.getId())) {
                    UserSessionModel oldSession = session.sessions().getUserSession(realm, oldSessionId);
                    if (oldSession != null) {
                        log.debugv("Removing old user session: session: {0}", oldSessionId);
                        session.sessions().removeUserSession(realm, oldSession);
                    }
                }
            }
        }

        // refresh the cookies!
        authManager.createLoginCookie(realm, accessCode.getUser(), userSession, uriInfo, clientConnection);
        if (userSession.isRememberMe()) authManager.createRememberMeCookie(realm, uriInfo, clientConnection);
        return location.build();
    }

    public Response redirectError(ClientModel client, String error, String state, String redirect) {
        UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam(OAuth2Constants.ERROR, error);
        if (state != null) {
            redirectUri.queryParam(OAuth2Constants.STATE, state);
        }
        return Response.status(302).location(redirectUri.build()).build();
    }

    public Response processAccessCode(String scopeParam, String state, String redirect, ClientModel client, UserModel user, UserSessionModel session, EventBuilder event) {
        isTotpConfigurationRequired(user);
        isEmailVerificationRequired(user);

        boolean isResource = client instanceof ApplicationModel;
        AccessCode accessCode = tokenManager.createAccessCode(scopeParam, state, redirect, this.session, realm, client, user, session);

        log.debugv("processAccessCode: isResource: {0}", isResource);
        log.debugv("processAccessCode: go to oauth page?: {0}",
                !isResource);

        event.detail(Details.CODE_ID, accessCode.getCodeId());

        Set<RequiredAction> requiredActions = user.getRequiredActions();
        if (!requiredActions.isEmpty()) {
            RequiredAction action = user.getRequiredActions().iterator().next();
            accessCode.setRequiredAction(action);

            if (action.equals(RequiredAction.VERIFY_EMAIL)) {
                event.clone().event(EventType.SEND_VERIFY_EMAIL).detail(Details.EMAIL, accessCode.getUser().getEmail()).success();
            }

            return Flows.forms(this.session, realm, client, uriInfo).setAccessCode(accessCode.getCode()).setUser(user)
                    .createResponse(action);
        }

        if (!isResource) {
            accessCode.setAction(ClientSessionModel.Action.OAUTH_GRANT);

            List<RoleModel> realmRoles = new LinkedList<RoleModel>();
            MultivaluedMap<String, RoleModel> resourceRoles = new MultivaluedMapImpl<String, RoleModel>();
            for (RoleModel r : accessCode.getRequestedRoles()) {
                if (r.getContainer() instanceof RealmModel) {
                    realmRoles.add(r);
                } else {
                    resourceRoles.add(((ApplicationModel) r.getContainer()).getName(), r);
                }
            }

            return Flows.forms(this.session, realm, client, uriInfo)
                    .setAccessCode(accessCode.getCode())
                    .setAccessRequest(realmRoles, resourceRoles)
                    .setClient(client)
                    .createOAuthGrant();
        }

        if (redirect != null) {
            event.success();

            accessCode.setAction(ClientSessionModel.Action.CODE_TO_TOKEN);
            return redirectAccessCode(accessCode, session, state, redirect);
        } else {
            return null;
        }
    }

    public Response forwardToSecurityFailure(String message) {
        return Flows.forms(session, realm, null, uriInfo).setError(message).createErrorPage();
    }

    private void isTotpConfigurationRequired(UserModel user) {
        for (RequiredCredentialModel c : realm.getRequiredCredentials()) {
            if (c.getType().equals(CredentialRepresentation.TOTP) && !user.isTotp()) {
                user.addRequiredAction(RequiredAction.CONFIGURE_TOTP);
                log.debug("User is required to configure totp");
            }
        }
    }

    private void isEmailVerificationRequired(UserModel user) {
        if (realm.isVerifyEmail() && !user.isEmailVerified()) {
            user.addRequiredAction(RequiredAction.VERIFY_EMAIL);
            log.debug("User is required to verify email");
        }
    }

}
