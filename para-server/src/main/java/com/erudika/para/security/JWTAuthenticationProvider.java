/*
 * Copyright 2013-2015 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.Para;
import com.erudika.para.core.App;
import com.erudika.para.utils.Config;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * An authentication provider that verifies JSON web tokens.
 * It uses the Nimbus JOSE + JWT library to validate tokens.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class JWTAuthenticationProvider implements AuthenticationProvider {

	/**
	 * Default constructor
	 */
	public JWTAuthenticationProvider() {
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		JWTAuthentication jwtToken = (JWTAuthentication) authentication;
		if (jwtToken != null) {
			if (!supports(authentication.getClass())) {
				return null;
			}
			try {
				SignedJWT jwt = jwtToken.getJwt();
				App app = Para.getDAO().read(jwtToken.getAppid());
				if (app != null) {
					JWSVerifier verifier = new MACVerifier(app.getSecret());
					if (jwt.verify(verifier)) {
						Date referenceTime = new Date();
						JWTClaimsSet claims = jwtToken.getClaims();

						Date expirationTime = claims.getExpirationTime();
						if (expirationTime == null || expirationTime.before(referenceTime)) {
							throw new BadCredentialsException("Expired token.");
						}

						Date notBeforeTime = claims.getNotBeforeTime();
						if (notBeforeTime == null || notBeforeTime.after(referenceTime)) {
							throw new BadCredentialsException("Invalid 'nbf' claim.");
						}

						String issuerReference = Config.getConfigParam("jwt.issuer", "paraio.org");
						String issuer = claims.getIssuer();
						if (!issuerReference.equals(issuer)) {
							throw new BadCredentialsException("Invalid issuer.");
						}
//						jwtToken.setAuthenticated(true);
					} else {
						throw new BadCredentialsException("Invalid token signature.");
					}
				} else {
					throw new AuthenticationServiceException("App not found.");
				}
			} catch (JOSEException e) {
				throw new BadCredentialsException("Invalid token signature.");
			}
		} else {
			throw new BadCredentialsException("Unsupported token type.");
		}
		return jwtToken;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return JWTAuthentication.class.isAssignableFrom(authentication);
	}
}
