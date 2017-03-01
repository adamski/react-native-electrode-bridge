package com.walmartlabs.electrode.reactnative.bridge;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.PromiseImpl;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.walmartlabs.electrode.reactnative.bridge.helpers.ArgumentsEx;
import com.walmartlabs.electrode.reactnative.bridge.helpers.Logger;
import com.walmartlabs.electrode.reactnative.bridge.util.BridgeArguments;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ElectrodeBridgeInternal extends ReactContextBaseJavaModule implements ElectrodeBridge {

    private static final String TAG = ElectrodeBridgeInternal.class.getSimpleName();

    public static final String BRIDGE_EVENT = "electrode.bridge.event";
    public static final String BRIDE_REQUEST = "electrode.bridge.request";
    public static final String BRIDGE_RESPONSE = "electrode.bridge.response";
    public static final String BRIDGE_RESPONSE_ERROR = "error";
    public static final String BRIDGE_RESPONSE_ERROR_CODE = "code";
    public static final String BRIDGE_RESPONSE_ERROR_MESSAGE = "message";
    public static final String BRIDGE_MSG_DATA = "data";
    public static final String BRIDGE_MSG_NAME = "name";
    public static final String BRIDGE_MSG_ID = "id";
    public static final String BRIDGE_REQUEST_ID = "requestId";
    public static final String UNKNOWN_ERROR_CODE = "EUNKNOWN";

    private final ReactContextWrapper mReactContextWrapper;
    private final EventDispatcher mEventDispatcher;
    private final RequestDispatcher mRequestDispatcher;

    // Singleton instance of the bridge
    private static ElectrodeBridgeInternal sInstance;

    private final ConcurrentHashMap<String, Promise> pendingPromiseByRequestId = new ConcurrentHashMap<>();
    private final EventRegistrar<ElectrodeBridgeEventListener> mEventRegistrar = new EventRegistrarImpl<>();
    private final RequestRegistrar<ElectrodeBridgeRequestHandler> mRequestRegistrar = new RequestRegistrarImpl<>();

    private static boolean sIsReactNativeReady;

    /**
     * Initializes a new instance of ElectrodeBridgeInternal
     *
     * @param reactContextWrapper The react application context
     */
    private ElectrodeBridgeInternal(@NonNull ReactContextWrapper reactContextWrapper) {
        super(reactContextWrapper.getContext());
        mReactContextWrapper = reactContextWrapper;
        mEventDispatcher = new EventDispatcherImpl(mEventRegistrar);
        mRequestDispatcher = new RequestDispatcherImpl(mRequestRegistrar);
    }

    /**
     * Creates the ElectrodeBridgeInternal singleton
     *
     * @param reactApplicationContext The react application context
     * @return The singleton instance of ElectrodeBridgeInternal
     */
    public static ElectrodeBridgeInternal create(ReactApplicationContext reactApplicationContext) {
        return create(new ReactContextWrapperInternal(reactApplicationContext));
    }

    /**
     * Creates the ElectrodeBridgeInternal singleton
     *
     * @param reactContextWrapper
     * @return The singleton instance of ElectrodeBridgeInternal
     */
    @VisibleForTesting
    static ElectrodeBridgeInternal create(ReactContextWrapper reactContextWrapper) {
        Logger.d(TAG, "Creating ElectrodeBridgeInternal instance");
        synchronized (ElectrodeBridgeInternal.class) {
            if (sInstance == null) {
                sInstance = new ElectrodeBridgeInternal(reactContextWrapper);
            }
        }
        return sInstance;
    }

    /**
     * Returns the singleton instance of the bridge
     */
    public static ElectrodeBridgeInternal instance() {
        if (sInstance == null) {
            throw new IllegalStateException("Bridge singleton has not been created. Make sure to call create first.");
        }
        return sInstance;
    }

    /**
     * @return the name of this module. This will be the name used to {@code require()} this module
     * from javascript.
     */
    @Override
    public String getName() {
        return "ElectrodeBridge";
    }

    /**
     * Provides a method to dispatch an event
     */
    public interface EventDispatcher {
        /**
         * Dispatch an event
         *
         * @param id   The event id
         * @param name The name of the event to dispatch
         * @param data The data of the event as a ReadableMap
         */
        void dispatchEvent(@NonNull String id,
                           @NonNull String name,
                           @NonNull ReadableMap data);

    }

    /**
     * Provides a method to dispatch a request
     */
    public interface RequestDispatcher {
        /**
         * Dispatch a request to the handler registered on native side.
         *
         * @param name    The name of the request to dispatch
         * @param id      The request id
         * @param data    The data of the request as a ReadableMap
         * @param promise A promise to fulfil upon request completion
         */
        void dispatchRequest(@NonNull String name,
                             @NonNull String id,
                             @NonNull Bundle data,
                             @NonNull Promise promise);

        void dispatchJSOriginatingRequest(@NonNull String name,
                                          @NonNull String id,
                                          @NonNull Bundle data,
                                          @NonNull Promise promise);

        boolean canHandleRequest(@NonNull String name);
    }

    /**
     * @return The event listener register
     */
    public EventRegistrar<ElectrodeBridgeEventListener> eventRegistrar() {
        return mEventRegistrar;
    }

    /**
     * @return The request handler registrar
     */
    public RequestRegistrar<ElectrodeBridgeRequestHandler> requestRegistrar() {
        return mRequestRegistrar;
    }

    @NonNull
    @Override
    public UUID addEventListener(@NonNull String name, @NonNull ElectrodeBridgeEventListener eventListener) {
        Logger.d(TAG, "Adding eventListener(%s) for event(%s)", eventListener, name);
        return mEventRegistrar.registerEventListener(name, eventListener);
    }

    @Override
    public void registerRequestHandler(@NonNull String name, @NonNull ElectrodeBridgeRequestHandler requestHandler) {
        mRequestRegistrar.registerRequestHandler(name, requestHandler);
    }

    /**
     * Emits an event with some data to the JS react native side
     *
     * @param event The event to emit
     */
    @SuppressWarnings("unused")
    @Override
    public void emitEvent(@NonNull ElectrodeBridgeEvent event) {
        String id = getUUID();
        WritableMap message = buildMessage(id, event.getName(), Arguments.fromBundle(event.getData()));

        Log.d(TAG, String.format("Emitting event[name:%s id:%s]", event.getName(), id));

        if (event.getDispatchMode() == ElectrodeBridgeEvent.DispatchMode.JS) {
            mReactContextWrapper.emitEvent(BRIDGE_EVENT, message);
        } else if (event.getDispatchMode() == ElectrodeBridgeEvent.DispatchMode.NATIVE) {
            dispatchEvent(event.getName(), id, Arguments.fromBundle(event.getData()));
        } else if (event.getDispatchMode() == ElectrodeBridgeEvent.DispatchMode.GLOBAL) {
            mReactContextWrapper.emitEvent(BRIDGE_EVENT, message);
            dispatchEvent(event.getName(), id, Arguments.fromBundle(event.getData()));
        }
    }


    /**
     * Sends a request
     *
     * @param request            The request to send
     * @param responseListener Listener to be called upon request completion
     */
    @SuppressWarnings("unused")
    @Override
    public void sendRequest(
            @NonNull final ElectrodeBridgeRequest request,
            @NonNull final ElectrodeBridgeResponseListener responseListener) {
        final String id = getUUID();
        logRequest(request, id);

        final Promise promise = new PromiseImpl(new Callback() {
            @Override
            public void invoke(final Object... args) {
                Object obj = args[0];

                final Bundle bundle;
                if (obj instanceof Bundle) {
                    bundle = (Bundle) obj;
                } else if (obj instanceof ReadableMap) {
                    bundle = BridgeArguments.responseBundle((ReadableMap) obj, BRIDGE_MSG_DATA);
                } else {
                    throw new IllegalArgumentException("Response object type not supported: " + (obj != null ? obj.getClass() : null));
                }

                logResponse(bundle, id, request);

                // Already done when receiving a response event type in dispatchEvent
                // is that needed here ?
                removePromiseFromPendingList(id);

                mReactContextWrapper.runOnUiQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        responseListener.onSuccess(bundle);
                    }
                });
            }
        }, new Callback() {
            @Override
            public void invoke(final Object... args) {
                final WritableMap writableMap = (WritableMap) args[0];

                logFailure(writableMap.getString("code"), writableMap.getString("message"), id, request);

                // Already done when receiving a response event type in dispatchEvent
                // is that needed here ?
                removePromiseFromPendingList(id);

                mReactContextWrapper.runOnUiQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        responseListener.onFailure(BridgeFailureMessage.create(writableMap.getString("code"), writableMap.getString("message")));
                    }
                });
            }
        });

        pendingPromiseByRequestId.put(id, promise);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            public void run() {
                Logger.d(TAG, "Checking timeout for request(%s)", id);
                if (pendingPromiseByRequestId.containsKey(id)) {
                    Logger.d(TAG, "request(%s) timed out, reject promise(%s)", id, promise);
                    promise.reject("EREQUESTTIMEOUT", "Request timeout");
                } else {
                    Logger.d(TAG, "Ignoring timeout, request(%s) already completed", id);
                }
            }
        }, request.getTimeoutMs());

        if (mRequestDispatcher.canHandleRequest(request.getName())) {
            mRequestDispatcher.dispatchRequest(request.getName(), id, request.getData(), promise);
        } else {
            WritableMap message = buildMessage(id, request.getName(), Arguments.fromBundle(request.getData()));
            mReactContextWrapper.emitEvent(BRIDE_REQUEST, message);
        }
    }

    private boolean removePromiseFromPendingList(String id) {
        Promise p = pendingPromiseByRequestId.remove(id);
        if (p == null) {
            Logger.d(TAG, "Looks like the request(%s) already timed out, ignore the response received", id);
            return false;
        }
        return true;
    }

    private void logRequest(@NonNull ElectrodeBridgeRequest bridgeRequest, @NonNull String id) {
        Logger.d(TAG, ">>>>>>>> id = %s, Sending request(%s)", id, bridgeRequest);
    }

    private void logResponse(@NonNull Bundle responseBundle, @NonNull String id, ElectrodeBridgeRequest request) {
        Logger.d(TAG, "<<<<<<<< id = %s, Received response(%s) for request(%s)", id, responseBundle, request);
    }

    private void logFailure(@NonNull String code, @NonNull String message, @NonNull String id, ElectrodeBridgeRequest request) {
        Logger.d(TAG, "<<<<<<<< id = %s, Received failure(code=%s, message=%s) for request(%s)", id, code, message, request);
    }

    /**
     * Dispatch a request on the native side
     *
     * @param name    The name of the request
     * @param id      The request id
     * @param data    The request data
     * @param promise A promise to reject or resolve the request asynchronously
     */
    @ReactMethod
    @SuppressWarnings("unused")
    public void dispatchRequest(String name, String id, ReadableMap data, Promise promise) {
        Log.d(TAG, String.format("dispatchRequest[name:%s id:%s]", name, id));
        mRequestDispatcher.dispatchJSOriginatingRequest(name, id, ArgumentsEx.toBundle(data), promise);
    }

    /**
     * Dispatch an event on the native side
     *
     * @param name The name of the event
     * @param id   The id of the event
     * @param data The event data
     */
    @ReactMethod
    @SuppressWarnings("unused")
    public void dispatchEvent(final String name, final String id, final ReadableMap data) {
        Logger.d(TAG, "inside dispatchEvent - onEvent[name:%s id:%s]", name, id);

        // This event represents a bridge response
        if (name.equals(BRIDGE_RESPONSE)) {
            Logger.d(TAG, "Handling bridge response event");
            // Get id of associated request
            String parentRequestId = data.getString(BRIDGE_REQUEST_ID);
            Log.d(TAG, String.format("Received response [id:%s]", parentRequestId));
            // Get the pending promise of the associated request
            Promise promise = pendingPromiseByRequestId.remove(parentRequestId);
            // If this is an error response
            // Reject the pending promise with error code and message
            if (data.hasKey(BRIDGE_RESPONSE_ERROR)) {
                String errorMessage = data
                        .getMap(BRIDGE_RESPONSE_ERROR)
                        .getString(BRIDGE_RESPONSE_ERROR_MESSAGE);

                String errorCode = UNKNOWN_ERROR_CODE;
                if (data.getMap(BRIDGE_RESPONSE_ERROR)
                        .hasKey(BRIDGE_RESPONSE_ERROR_CODE)) {
                    errorCode = data
                            .getMap(BRIDGE_RESPONSE_ERROR)
                            .getString(BRIDGE_RESPONSE_ERROR_CODE);
                }
                promise.reject(errorCode, errorMessage);
            }
            // If this is a success response with a payload
            // Resolve the promise with the data
            else if (data.hasKey(BRIDGE_MSG_DATA)) {
                promise.resolve(data);
            }
            // If this is a success response without a payload
            // Resolve the promise with null
            else if (!data.hasKey(BRIDGE_MSG_DATA)) {
                promise.resolve(null);
            }
            // Unknown type of response :S
            else {
                promise.reject(new UnsupportedOperationException());
            }
        } else {
            Logger.d(TAG, "Handling regular event");
            mReactContextWrapper.runOnUiQueueThread(new Runnable() {
                @Override
                public void run() {
                    mEventDispatcher.dispatchEvent(id, name, data);
                }
            });
        }
    }

    private String getUUID() {
        return UUID.randomUUID().toString();
    }

    private WritableMap buildMessage(String id, String name, WritableMap data) {
        WritableMap writableMap = Arguments.createMap();
        writableMap.putString(BRIDGE_MSG_ID, id);
        writableMap.putString(BRIDGE_MSG_NAME, name);
        writableMap.putMap(BRIDGE_MSG_DATA, data);

        return writableMap;
    }

    public interface ReactNativeReadyListener {
        void onReactNativeReady();
    }

    private static ReactNativeReadyListener sReactNativeReadyListener;

    public static void registerReactNativeReadyListener(ReactNativeReadyListener listener) {
        // If react native initialization is already completed, just call listener
        // immediately
        if (sIsReactNativeReady) {
            listener.onReactNativeReady();
        }
        // Else it will get invoked whenever react native initialization is done
        else {
            sReactNativeReadyListener = listener;
        }
    }

    public void onReactNativeInitialized() {
        sIsReactNativeReady = true;
        if (sReactNativeReadyListener != null) {
            sReactNativeReadyListener.onReactNativeReady();
        }
    }
}
