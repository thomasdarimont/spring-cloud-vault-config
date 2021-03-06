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

import java.util.*;

import org.springframework.cloud.vault.VaultProperties.AppIdProperties;
import org.springframework.cloud.vault.VaultProperties.AuthenticationMethod;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.util.Assert;

import lombok.extern.apachecommons.CommonsLog;

/**
 * @author Spencer Gibb
 * @author Mark Paluch
 */
@CommonsLog
public class VaultPropertySource extends EnumerablePropertySource<VaultClient> {

	private final VaultProperties vaultProperties;

	private String context;
	private Map<String, String> properties = new LinkedHashMap<>();

	private transient VaultState vaultState;

	public VaultPropertySource(String context, VaultClient source,
			VaultProperties properties, VaultState state) {
		super(context, source);
		this.context = context;
		this.vaultProperties = properties;
		this.vaultState = state;
	}

	public void init() {

		Assert.hasText(vaultProperties.getBackend(),
				"No generic secret backend configured (spring.cloud.vault.backend)");

		List<SecureBackendAccessor> accessors = getSecureBackendAccessors();

		for (SecureBackendAccessor accessor : accessors) {
			try {
				Map<String, String> values = this.source.read(accessor, obtainToken());

				if (values != null) {
					this.properties.putAll(values);
				}
			}
			catch (Exception e) {

				String message = String.format(
						"Unable to read properties from vault for %s ",
						accessor.variables());
				if (vaultProperties.isFailFast()) {
					if (e instanceof RuntimeException) {
						throw e;
					}

					throw new IllegalStateException(message, e);
				}

				log.error(message, e);
			}
		}
	}

	private List<SecureBackendAccessor> getSecureBackendAccessors() {

		List<SecureBackendAccessor> accessors = new ArrayList<>();

		accessors.add(SecureBackendAccessors.generic(vaultProperties.getBackend(),
				this.context));

		VaultProperties.MySql mySql = vaultProperties.getMysql();
		if (mySql.isEnabled()) {
			accessors.add(SecureBackendAccessors.database(mySql));
		}

		VaultProperties.PostgreSql postgreSql = vaultProperties.getPostgresql();
		if (postgreSql.isEnabled()) {
			accessors.add(SecureBackendAccessors.database(postgreSql));
		}

		VaultProperties.Cassandra cassandra = vaultProperties.getCassandra();
		if (cassandra.isEnabled()) {
			accessors.add(SecureBackendAccessors.database(cassandra));
		}
		return accessors;
	}

	private VaultToken obtainToken() {

		if (vaultState.getToken() != null) {
			return vaultState.getToken();
		}

		if (vaultProperties.getAuthentication() == AuthenticationMethod.TOKEN) {

			Assert.hasText(vaultProperties.getToken(), "Token must not be empty");
			vaultState.setToken(VaultToken.of(vaultProperties.getToken()));

			return vaultState.getToken();
		}

		if (vaultProperties.getAuthentication() == AuthenticationMethod.APPID) {

			AppIdProperties appIdProperties = vaultProperties.getAppId();
			Assert.hasText(vaultProperties.getApplicationName(),
					"AppId must not be empty");
			Assert.hasText(appIdProperties.getAppIdPath(), "AppIdPath must not be empty");

			vaultState.setToken(source.createToken());
			return vaultState.getToken();
		}

		throw new IllegalStateException(
				String.format("Authentication method %s not supported",
						vaultProperties.getAuthentication()));
	}

	@Override
	public Object getProperty(String name) {
		return this.properties.get(name);
	}

	@Override
	public String[] getPropertyNames() {
		Set<String> strings = this.properties.keySet();
		return strings.toArray(new String[strings.size()]);
	}
}