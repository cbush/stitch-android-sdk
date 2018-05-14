/*
 * Copyright 2018-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.stitch.core.auth.internal;

import static com.mongodb.stitch.core.testutils.ApiTestUtils.getAuthorizationBearer;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getMockedRequestClient;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestAccessToken;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestLinkResponse;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestLoginResponse;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestRefreshToken;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestUserProfile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.stitch.core.StitchRequestException;
import com.mongodb.stitch.core.StitchServiceErrorCode;
import com.mongodb.stitch.core.StitchServiceException;
import com.mongodb.stitch.core.auth.internal.models.ApiCoreUserProfile;
import com.mongodb.stitch.core.auth.providers.anonymous.AnonymousAuthProvider;
import com.mongodb.stitch.core.auth.providers.anonymous.AnonymousCredential;
import com.mongodb.stitch.core.auth.providers.userpass.UserPasswordAuthProvider;
import com.mongodb.stitch.core.auth.providers.userpass.UserPasswordCredential;
import com.mongodb.stitch.core.internal.common.BsonUtils;
import com.mongodb.stitch.core.internal.common.MemoryStorage;
import com.mongodb.stitch.core.internal.common.StitchObjectMapper;
import com.mongodb.stitch.core.internal.common.Storage;
import com.mongodb.stitch.core.internal.net.ContentTypes;
import com.mongodb.stitch.core.internal.net.Headers;
import com.mongodb.stitch.core.internal.net.Method;
import com.mongodb.stitch.core.internal.net.Response;
import com.mongodb.stitch.core.internal.net.StitchAppRoutes;
import com.mongodb.stitch.core.internal.net.StitchAuthDocRequest;
import com.mongodb.stitch.core.internal.net.StitchDocRequest;
import com.mongodb.stitch.core.internal.net.StitchRequest;
import com.mongodb.stitch.core.internal.net.StitchRequestClient;
import com.mongodb.stitch.core.testutils.CustomType;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.IntegerCodec;
import org.bson.codecs.StringCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.ObjectId;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class CoreStitchAuthUnitTests {

  @Test
  public void testLoginWithCredentialBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    final CoreStitchUser user =
        auth.loginWithCredentialInternal(new AnonymousCredential());

    final ApiCoreUserProfile profile = getTestUserProfile();
    assertEquals(getTestLoginResponse().getUserId(), user.getId());
    assertEquals(AnonymousAuthProvider.DEFAULT_NAME, user.getLoggedInProviderName());
    assertEquals(AnonymousAuthProvider.TYPE, user.getLoggedInProviderType());
    assertEquals(profile.getUserType(), user.getUserType());
    assertEquals(profile.getIdentities().get(0).getId(), user.getIdentities().get(0).getId());
    assertEquals(auth.getUser(), user);
    assertTrue(auth.isLoggedIn());

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(2)).doRequest(reqArgs.capture());

    final StitchDocRequest.Builder expectedRequest = new StitchDocRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withPath(routes.getAuthProviderLoginRoute(AnonymousAuthProvider.DEFAULT_NAME));
    expectedRequest.withDocument(new Document("options", new Document("device", new Document())));
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(0));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest2.withMethod(Method.GET)
        .withPath(routes.getProfileRoute())
        .withHeaders(headers);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(1));
  }

  @Test
  public void testLinkUserWithCredentialBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    final CoreStitchUser user =
        auth.loginWithCredentialInternal(new AnonymousCredential());
    verify(requestClient, times(2)).doRequest(any());

    final CoreStitchUser linkedUser =
        auth.linkUserWithCredentialInternal(
            user,
            new UserPasswordCredential("foo@foo.com", "bar"));

    assertEquals(user.getId(), linkedUser.getId());

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(4)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withBody(String.format(
            "{\"username\" : \"foo@foo.com\",\"password\" : \"bar\","
            + "\"options\" : {\"device\" : {\"deviceId\" : \"%s\"}}}",
            getTestLoginResponse().getDeviceId()).getBytes(StandardCharsets.UTF_8))
        .withPath(routes.getAuthProviderLinkRoute(UserPasswordAuthProvider.DEFAULT_NAME));
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.CONTENT_TYPE, ContentTypes.APPLICATION_JSON);
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest.withHeaders(headers);

    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(2));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    final Map<String, String> headers2 = new HashMap<>();
    headers2.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest2.withMethod(Method.GET)
        .withPath(routes.getProfileRoute())
        .withHeaders(headers2);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(3));
  }

  @Test
  public void testIsLoggedIn() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    assertFalse(auth.isLoggedIn());

    auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.isLoggedIn());
  }

  @Test
  public void testLogoutBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    assertFalse(auth.isLoggedIn());

    auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.isLoggedIn());

    auth.logoutInternal();

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(3)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.DELETE)
        .withPath(routes.getSessionRoute());
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestRefreshToken()));
    expectedRequest.withHeaders(headers);
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(2));

    assertFalse(auth.isLoggedIn());
  }

  @Test
  public void testHasDeviceId() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    assertFalse(auth.hasDeviceId());

    auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.hasDeviceId());
  }

  @Test
  public void testHandleAuthFailure() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    final CoreStitchUser user = auth.loginWithCredentialInternal(new AnonymousCredential());

    final Map<String, Object> claims = new HashMap<>();
    claims.put("typ", "access");
    claims.put("test_refreshed", true);
    final String refreshedJwt = Jwts.builder()
        .setClaims(claims)
        .setIssuedAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
        .setSubject("uniqueUserID")
        .setExpiration(new Date(((Calendar.getInstance().getTimeInMillis() + (5 * 60 * 1000)))))
        .signWith(
            SignatureAlgorithm.HS256,
            "abcdefghijklmnopqrstuvwxyz1234567890".getBytes(StandardCharsets.UTF_8))
        .compact();
    doReturn(new Response(new Document("access_token", refreshedJwt).toJson()))
        .when(requestClient)
        .doRequest(argThat(req -> req.getMethod() == Method.POST
            && req.getPath().endsWith("/session")));

    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .doReturn(
            new Response(getTestLinkResponse().toString()))
        .when(requestClient)
        .doRequest(argThat(req -> req.getPath().endsWith("/login?link=true")));

    final CoreStitchUser linkedUser =
        auth.linkUserWithCredentialInternal(
            user,
            new UserPasswordCredential("foo@foo.com", "bar"));

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(6)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withPath(routes.getSessionRoute());
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestRefreshToken()));
    expectedRequest.withHeaders(headers);
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(3));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    expectedRequest2.withMethod(Method.POST)
        .withBody(String.format(
            "{\"username\" : \"foo@foo.com\",\"password\" : \"bar\","
            + "\"options\" : {\"device\" : {\"deviceId\" : \"%s\"}}}",
            getTestLoginResponse().getDeviceId()).getBytes(StandardCharsets.UTF_8))
        .withPath(routes.getAuthProviderLinkRoute(UserPasswordAuthProvider.DEFAULT_NAME));
    final Map<String, String> headers2 = new HashMap<>();
    headers2.put(Headers.CONTENT_TYPE, ContentTypes.APPLICATION_JSON);
    headers2.put(Headers.AUTHORIZATION, getAuthorizationBearer(refreshedJwt));
    expectedRequest2.withHeaders(headers2);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(4));

    assertTrue(auth.isLoggedIn());

    // This should log the user out
    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .when(requestClient)
        .doRequest(argThat(req -> req.getMethod() == Method.POST
            && req.getPath().endsWith("/session")));
    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .when(requestClient)
        .doRequest(argThat(req -> req.getPath().endsWith("/login?link=true")));

    try {
      auth.linkUserWithCredentialInternal(
          linkedUser,
          new UserPasswordCredential("foo@foo.com", "bar"));
      fail();
    } catch (final StitchServiceException ex) {
      assertEquals(ex.getErrorCode(), StitchServiceErrorCode.INVALID_SESSION);
    }

    assertFalse(auth.isLoggedIn());
  }

  @Test
  public void testDoAuthenticatedJsonRequestWithDefaultCodecRegistry() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());
    auth.loginWithCredentialInternal(new AnonymousCredential());

    final StitchAuthDocRequest.Builder reqBuilder = new StitchAuthDocRequest.Builder();
    reqBuilder.withPath("giveMeData");
    reqBuilder.withDocument(new Document());
    reqBuilder.withMethod(Method.POST);

    final String rawInt = "{\"$numberInt\": \"42\"}";
    // Check that primitive return types can be decoded.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    assertEquals(42, (int) auth.doAuthenticatedJsonRequest(reqBuilder.build(), Integer.class));
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    assertEquals(42, (int) auth.doAuthenticatedJsonRequest(reqBuilder.build(), new IntegerCodec()));

    // Check that the proper exceptions are thrown when decoding into the incorrect type.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    try {
      auth.doAuthenticatedJsonRequest(reqBuilder.build(), String.class);
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    try {
      auth.doAuthenticatedJsonRequest(reqBuilder.build(), new StringCodec());
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    // Check that BSON documents returned as extended JSON can be decoded.
    final ObjectId expectedObjectId = new ObjectId();
    final String docRaw =
        String.format(
            "{\"_id\": {\"$oid\": \"%s\"}, \"intValue\": {\"$numberInt\": \"42\"}}",
            expectedObjectId.toHexString());
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());

    Document doc = auth.doAuthenticatedJsonRequest(reqBuilder.build(), Document.class);
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    doc = auth.doAuthenticatedJsonRequest(reqBuilder.build(), new DocumentCodec());
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    // Check that BSON documents returned as extended JSON can be decoded as a custom type if
    // the codec is specifically provided.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    final CustomType ct =
        auth.doAuthenticatedJsonRequest(reqBuilder.build(), new CustomType.Codec());
    assertEquals(expectedObjectId, ct.getId());
    assertEquals(42, ct.getIntValue());

    // Check that the correct exception is thrown if attempting to decode as a particular class
    // type if the auth was never configured to contain the provided class type
    // codec.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    try {
      auth.doAuthenticatedJsonRequest(reqBuilder.build(), CustomType.class);
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    // Check that BSON arrays can be decoded
    final List<Object> arrFromServer =
        Arrays.asList(21, "the meaning of life, the universe, and everything", 84, 168);
    final String arrFromServerRaw;
    try {
      arrFromServerRaw = StitchObjectMapper.getInstance().writeValueAsString(arrFromServer);
    } catch (final JsonProcessingException e) {
      fail(e.getMessage());
      return;
    }
    doReturn(new Response(arrFromServerRaw)).when(requestClient).doRequest(any());

    @SuppressWarnings("unchecked")
    final List<Object> list = auth.doAuthenticatedJsonRequest(reqBuilder.build(), List.class);
    assertEquals(arrFromServer, list);
  }

  @Test
  public void testDoAuthenticatedJsonRequestWithCustomCodecRegistry() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth =
        new StitchAuth(
            requestClient,
            routes,
            new MemoryStorage(),
            CodecRegistries.fromRegistries(
                BsonUtils.DEFAULT_CODEC_REGISTRY,
                CodecRegistries.fromCodecs(new CustomType.Codec())));
    auth.loginWithCredentialInternal(new AnonymousCredential());

    final StitchAuthDocRequest.Builder reqBuilder = new StitchAuthDocRequest.Builder();
    reqBuilder.withPath("giveMeData");
    reqBuilder.withDocument(new Document());
    reqBuilder.withMethod(Method.POST);

    final String rawInt = "{\"$numberInt\": \"42\"}";
    // Check that primitive return types can be decoded.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    assertEquals(42, (int) auth.doAuthenticatedJsonRequest(reqBuilder.build(), Integer.class));
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any());
    assertEquals(42, (int) auth.doAuthenticatedJsonRequest(reqBuilder.build(), new IntegerCodec()));

    final ObjectId expectedObjectId = new ObjectId();
    final String docRaw =
        String.format(
            "{\"_id\": {\"$oid\": \"%s\"}, \"intValue\": {\"$numberInt\": \"42\"}}",
            expectedObjectId.toHexString());

    // Check that BSON documents returned as extended JSON can be decoded into BSON
    // documents.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    Document doc = auth.doAuthenticatedJsonRequest(reqBuilder.build(), Document.class);
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    doc = auth.doAuthenticatedJsonRequest(reqBuilder.build(), new DocumentCodec());
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    // Check that a custom type can be decoded without providing a codec, as long as that codec
    // is registered in the CoreStitchAuth's configuration.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any());
    final CustomType ct = auth.doAuthenticatedJsonRequest(reqBuilder.build(), CustomType.class);
    assertEquals(expectedObjectId, ct.getId());
    assertEquals(42, ct.getIntValue());
  }

  public static class StitchAuth extends CoreStitchAuth<CoreStitchUserImpl> {
    StitchAuth(
        final StitchRequestClient requestClient,
        final StitchAuthRoutes authRoutes,
        final Storage storage) {
      super(requestClient, authRoutes, storage, false);
    }

    protected StitchAuth(
        final StitchRequestClient requestClient,
        final StitchAuthRoutes authRoutes,
        final Storage storage,
        final CodecRegistry codecRegistry) {
      super(requestClient, authRoutes, storage, codecRegistry, false);
    }

    @Override
    protected StitchUserFactory<CoreStitchUserImpl> getUserFactory() {
      return (String id,
          String loggedInProviderType,
          String loggedInProviderName,
          StitchUserProfileImpl userProfile) ->
          new CoreStitchUserImpl(id, loggedInProviderType, loggedInProviderName, userProfile) {};
    }

    @Override
    protected void onAuthEvent() {}
  }
}