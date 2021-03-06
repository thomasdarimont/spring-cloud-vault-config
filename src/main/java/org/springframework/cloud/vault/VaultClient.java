/*
 * Copyright 2013-2015 the original author or authors.
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
 */
package org.springframework.cloud.vault;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.vault.VaultProperties.AppIdProperties;
import org.springframework.cloud.vault.VaultProperties.AuthenticationMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import lombok.Setter;
import lombok.Value;
import lombok.extern.apachecommons.CommonsLog;

/**
 * Vault client. This client reads data from Vault secret backends and can authenticate
 * with Vault to obtain an access token.
 *
 * @author Spencer Gibb
 * @author Mark Paluch
 */
@CommonsLog
public class VaultClient {

	public static final String API_VERSION = "v1";
	public static final String VAULT_TOKEN = "X-Vault-Token";

	@Setter
	private RestTemplate rest = new RestTemplate();

	@Setter
	private AppIdUserIdMechanism appIdUserIdMechanism;

	private ClientHttpRequestFactory clientHttpRequestFactory;
	private final VaultProperties properties;

	public VaultClient(VaultProperties properties) {

		Assert.notNull(properties, "VaultProperties must not be null");

		this.properties = properties;
	}

	public Map<String, String> read(SecureBackendAccessor secureBackendAccessor,
			VaultToken vaultToken) {

		Assert.notNull(secureBackendAccessor, "SecureBackendAccessor must not be empty!");
		Assert.notNull(vaultToken, "VaultToken must not be null!");

		String url = buildUrl();

		HttpHeaders headers = createHeaders(vaultToken);
		Exception error = null;
		String errorBody = null;

		URI uri = this.rest.getUriTemplateHandler().expand(url, secureBackendAccessor.variables());
		log.info(String.format("Fetching config from server at: %s", uri));

		try {
			ResponseEntity<VaultResponse> response = this.rest.exchange(uri,
					HttpMethod.GET, new HttpEntity<>(headers), VaultResponse.class);

			HttpStatus status = response.getStatusCode();
			if (status == HttpStatus.OK) {
				if (response.getBody().getData() != null) {
					return secureBackendAccessor
							.transformProperties(response.getBody().getData());
				}
			}
		}
		catch (HttpServerErrorException e) {
			error = e;
			if (MediaType.APPLICATION_JSON
					.includes(e.getResponseHeaders().getContentType())) {
				errorBody = e.getResponseBodyAsString();
			}
		}
		catch (Exception e) {
			error = e;
		}

		if (properties.isFailFast()) {
			throw new IllegalStateException(
					"Could not locate PropertySource and the fail fast property is set, failing",
					error);
		}

		log.warn(
				String.format("Could not locate PropertySource: %s",
						(errorBody == null
								? error == null ? "key not found" : error.getMessage()
								: errorBody)));

		return Collections.emptyMap();
	}

	private HttpHeaders createHeaders(VaultToken vaultToken) {

		HttpHeaders headers = new HttpHeaders();
		headers.add(VAULT_TOKEN, vaultToken.getToken());
		return headers;
	}

	/**
	 * Creates a token using a configured authentication mechanism.
	 *
	 * @return the {@link VaultToken}.
	 */
	public VaultToken createToken() {

		if (properties.getAuthentication() == AuthenticationMethod.APPID
				&& appIdUserIdMechanism != null) {
			AppIdProperties appId = properties.getAppId();
			return createTokenUsingAppId(new AppIdTuple(properties.getApplicationName(),
					appIdUserIdMechanism.createUserId()), appId);
		}

		throw new UnsupportedOperationException(
				String.format("Cannot create a token for auth method %s",
						properties.getAuthentication()));
	}

	private VaultToken createTokenUsingAppId(AppIdTuple appIdTuple,
			AppIdProperties appId) {

		String url = buildUrl();
		Map<String, String> variables = new HashMap<>();
		variables.put("backend", "auth/" + appId.getAppIdPath());
		variables.put("key", "login");

		Map<String, String> login = getAppIdLogin(appIdTuple);

		try {
			ResponseEntity<VaultResponse> response = this.rest.exchange(url,
					HttpMethod.POST, new HttpEntity<>(login), VaultResponse.class,
					variables);

			HttpStatus status = response.getStatusCode();
			if (!status.is2xxSuccessful()) {
				throw new IllegalStateException("Cannot login using app-id");
			}

			VaultResponse body = response.getBody();
			String token = (String) body.getAuth().get("client_token");

			return VaultToken.of(token, body.getLeaseDuration());
		}
		catch (HttpClientErrorException e) {

			if (e.getStatusCode().equals(HttpStatus.BAD_REQUEST)) {
				throw new IllegalStateException(String.format(
						"Cannot login using app-id: %s", e.getResponseBodyAsString()));
			}

			throw e;
		}
	}

	private Map<String, String> getAppIdLogin(AppIdTuple appIdTuple) {

		Map<String, String> login = new HashMap<>();
		login.put("app_id", appIdTuple.getAppId());
		login.put("user_id", appIdTuple.getUserId());
		return login;
	}

	private String buildUrl() {
		return String.format("%s://%s:%s/%s/{backend}/{key}", this.properties.getScheme(),
				this.properties.getHost(), this.properties.getPort(), API_VERSION);
	}

	@Value
	private static class AppIdTuple {
		private String appId;
		private String userId;
	}
}
