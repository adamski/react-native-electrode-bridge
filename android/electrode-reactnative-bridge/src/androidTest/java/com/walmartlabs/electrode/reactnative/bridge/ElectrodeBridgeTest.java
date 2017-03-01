package com.walmartlabs.electrode.reactnative.bridge;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.walmartlabs.electrode.reactnative.sample.api.PersonApi;
import com.walmartlabs.electrode.reactnative.sample.model.Person;
import com.walmartlabs.electrode.reactnative.sample.model.Status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.annotation.Nonnull;

import static com.walmartlabs.electrode.reactnative.bridge.ElectrodeBridgeInternal.BRIDGE_MSG_DATA;

public class ElectrodeBridgeTest extends BaseBridgeTestCase {

    public void testSampleRequestNativeToNativeFailure() {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        PersonApi.requests().getUserName(new ElectrodeBridgeResponseListener<String>() {
            @Override
            public void onSuccess(String obj) {
                fail();
            }

            @Override
            public void onFailure(@Nonnull FailureMessage failureMessage) {
                assertNotNull(failureMessage);
                assertNotNull(failureMessage.getCode());
                assertNotNull(failureMessage.getMessage());
                countDownLatch.countDown();
            }
        });

        waitForCountDownToFinishOrFail(countDownLatch);
    }

    public void testSampleRequestNativeToJS() {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        final String expectedResult = "Richard Mercille";

        UUID uuid = addMockEventListener(PersonApi.Requests.REQUEST_GET_USER_NAME, new MockElectrodeEventListener() {
            @Override
            public void onEvent(@NonNull String eventName, @Nullable WritableMap message, @NonNull MockJsResponseDispatcher jsResponseDispatcher) {
                assertEquals(PersonApi.Requests.REQUEST_GET_USER_NAME, eventName);
                assertNotNull(message);
                WritableMap response = Arguments.createMap();
                response.putString(BRIDGE_MSG_DATA, expectedResult);
                jsResponseDispatcher.dispatchResponse(response);
                countDownLatch.countDown();
            }
        });

        PersonApi.requests().getUserName(new ElectrodeBridgeResponseListener<String>() {
            @Override
            public void onSuccess(String obj) {
                assertNotNull(expectedResult, obj);
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(@Nonnull FailureMessage failureMessage) {
                fail();
            }
        });

        waitForCountDownToFinishOrFail(countDownLatch);
        removeMockEventListener(uuid);

    }


    public void testRegisterGetStatusRequestHandlerNativeToNative() {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final Status result = new Status.Builder(true).log(true).build();
        final Person person = new Person.Builder("John", 05).build();

        PersonApi.requests().registerGetStatusRequestHandler(new ElectrodeBridgeRequestHandler<Person, Status>() {
            @Override
            public void onRequest(@Nullable Person payload, @NonNull ElectrodeBridgeResponseListener<Status> responseListener) {
                assertEquals(person.getName(), payload.getName());
                assertEquals(person.getMonth(), payload.getMonth());
                responseListener.onSuccess(result);
            }
        });


        PersonApi.requests().getStatus(person, new ElectrodeBridgeResponseListener<Status>() {
            @Override
            public void onSuccess(Status obj) {
                assertNotNull(obj);
                assertEquals(result.getLog(), obj.getLog());
                assertEquals(result.getMember(), obj.getMember());
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(@Nonnull FailureMessage failureMessage) {
                fail();
            }
        });

        waitForCountDownToFinishOrFail(countDownLatch);
    }

    public void testPrimitiveTypesForRequestAndResponseNativeToNative() {
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        PersonApi.requests().registerGetAgeRequestHandler(new ElectrodeBridgeRequestHandler<String, Integer>() {
            @Override
            public void onRequest(@Nullable String payload, @NonNull ElectrodeBridgeResponseListener<Integer> responseListener) {
                assertNotNull(payload);
                assertNotNull(responseListener);
                responseListener.onSuccess(30);
                countDownLatch.countDown();
            }
        });


        PersonApi.requests().getAge("deepu", new ElectrodeBridgeResponseListener<Integer>() {
            @Override
            public void onFailure(@NonNull FailureMessage failureMessage) {
                fail();
            }

            @Override
            public void onSuccess(@Nullable Integer responseData) {
                assertNotNull(responseData);
                assertSame(30, responseData);
                countDownLatch.countDown();
            }
        });

        waitForCountDownToFinishOrFail(countDownLatch);
    }


    public void testEventsForModelObjectNativeToNative() {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final Person person = new Person.Builder("chris", 20).build();
        PersonApi.events().addPersonAddedEventListener(new ElectrodeBridgeEventListener<Person>() {
            @Override
            public void onEvent(@Nullable Person eventPayload) {
                assertNotNull(eventPayload);
                assertEquals(person.getName(), eventPayload.getName());
                assertEquals(person.getMonth(), eventPayload.getMonth());
                countDownLatch.countDown();
            }
        });
        PersonApi.events().emitEventPersonAdded(person);
        waitForCountDownToFinishOrFail(countDownLatch);
    }

    public void testEventsForModelPrimitiveWrapperNativeToNative() {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final String personName = "chris";
        PersonApi.events().addPersonNameUpdatedEventListener(new ElectrodeBridgeEventListener<String>() {
            @Override
            public void onEvent(@Nullable String eventPayload) {
                assertNotNull(eventPayload);
                assertEquals(personName, eventPayload);
                countDownLatch.countDown();
            }
        });

        PersonApi.events().emitEventPersonNameUpdated(personName);
        waitForCountDownToFinishOrFail(countDownLatch);
    }
}