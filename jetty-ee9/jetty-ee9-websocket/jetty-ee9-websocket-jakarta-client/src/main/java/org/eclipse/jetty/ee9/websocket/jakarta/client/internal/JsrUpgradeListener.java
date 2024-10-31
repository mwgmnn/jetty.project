//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.client.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.websocket.ClientEndpointConfig.Configurator;
import jakarta.websocket.HandshakeResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;

public class JsrUpgradeListener implements UpgradeListener
{
    private final Configurator configurator;

    public JsrUpgradeListener(Configurator configurator)
    {
        this.configurator = configurator;
    }

    @Override
    public void onHandshakeRequest(Request request)
    {
        if (configurator == null)
            return;

        // Give headers to configurator
        HttpFields fields = request.getHeaders();
        Map<String, List<String>> originalHeaders = HttpFields.asMap(fields);
        configurator.beforeRequest(originalHeaders);

        // Reset headers on HttpRequest per configurator
        request.headers(headers ->
        {
            headers.clear();
            originalHeaders.forEach(headers::put);
        });
    }

    @Override
    public void onHandshakeResponse(Request request, Response response)
    {
        if (configurator == null)
            return;

        HandshakeResponse handshakeResponse = () -> Collections.unmodifiableMap(HttpFields.asMap(response.getHeaders()));
        configurator.afterResponse(handshakeResponse);
    }
}
