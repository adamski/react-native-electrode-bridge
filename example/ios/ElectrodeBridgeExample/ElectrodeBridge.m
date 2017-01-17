//
//  ElectrodeBridge.m
//  ElectrodeBridgeExample
//
//  Created by Cody Garvin on 12/12/16.
//  Copyright © 2016 Facebook. All rights reserved.
//

#import "ElectrodeBridge.h"
#import "ElectrodeEventDispatcher.h"
#import "ElectrodeEventRegistrar.h"
#import "ElectrodeBridgeEvent.h"
#import "RCTLog.h"
#import "RCTBridge.h"
#import "RCTEventDispatcher.h"
#import "ElectrodeBridgeHolder.h"
#import "ElectrodeRequestDispatcher.h"
#import "ElectrodeBridgeRequest.h"

NSString * const EBBridgeEvent = @"electrode.bridge.event";
NSString * const EBBridgeRequest = @"electrode.bridge.request";
NSString * const EBBridgeResponse = @"electrode.bridge.response";
NSString * const EBBridgeError = @"error";
NSString * const EBBridgeErrorCode = @"code";
NSString * const EBBridgeErrorMessage = @"message";
NSString * const EBBridgeMsgData = @"data";
NSString * const EBBridgeMsgName = @"name";
NSString * const EBBridgeMsgID = @"id";
NSString * const EBBridgeRequestID = @"requestId";
NSString * const EBBridgeUnknownError = @"EUNKNOWN";

typedef void (^ElectrodeBridgeRequestBlock)();

@interface ElectrodeBridge ()

@property (nonatomic, assign) BOOL usedPromise;
@property (nonatomic, strong) ElectrodeEventDispatcher *eventDispatcher;
@property (nonatomic, strong) ElectrodeRequestDispatcher *requestDispatcher;
@property (nonatomic, strong) NSMutableDictionary<NSString *, id<ElectrodeRequestCompletionListener>> *requestListeners;
@end

@implementation ElectrodeBridge

@synthesize bridge = _bridge;

- (instancetype)init
{
  self = [super init];
  if (self)
  {
    self.eventDispatcher = [[ElectrodeEventDispatcher alloc] init];
    self.requestDispatcher = [[ElectrodeRequestDispatcher alloc] init];
    [ElectrodeBridgeHolder sharedInstance].bridge = self;
  }
  return self;
}

RCT_EXPORT_MODULE();

RCT_EXPORT_METHOD(dispatchRequest:(NSString *)name id:(NSString *)id
                  data:(NSDictionary *)data
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  RCTLogInfo(@"dispatchRequest[name:%@ id:%@]", name, id);

  [self.requestDispatcher dispatchRequest:name id:id data:data completion:
   ^(NSDictionary *data, NSError *error)
  {
    if (!error)
    {
      resolve(data);
    }
    else
    {
      reject([NSString stringWithFormat:@"%zd", error.code], error.localizedDescription, error);
    }
  }];
}

RCT_EXPORT_METHOD(dispatchEvent:(NSString *)event
                  id:(NSString *)eventID
                  data:(NSDictionary *)data)
{
  RCTLogInfo(@"onEvent[name:%@ id:%@] %@", event, eventID, data);
  
  // Handle JS request responses here
  if ([event isEqualToString:EBBridgeResponse])
  {
    NSString *parentRequestID = [data objectForKey:EBBridgeRequestID];
    RCTLogInfo(@"Received response [id:%@", parentRequestID);
    
    
    id<ElectrodeRequestCompletionListener> listener = [_requestListeners objectForKey:parentRequestID];
    if (listener && [listener conformsToProtocol:@protocol(ElectrodeRequestCompletionListener)])
    {
      [_requestListeners removeObjectForKey:eventID];
      
      if ([data objectForKey:EBBridgeError])
      { // Grab the handler and reject it
        NSString *errorMessage = EBBridgeUnknownError;
        NSDictionary *errorData = [data objectForKey:EBBridgeError];
        if ([errorData isKindOfClass:[NSDictionary class]])
        {
          errorMessage = [errorData objectForKey:EBBridgeErrorMessage];
        }
        
        dispatch_async(dispatch_get_main_queue(), ^{
          [listener onError:EBBridgeUnknownError message:errorMessage];
        });
      }
      else if ([data objectForKey:EBBridgeMsgData])
      { // Grab the handler and accept it
        dispatch_async(dispatch_get_main_queue(), ^{
          [listener onSuccess:[data objectForKey:EBBridgeMsgData]];
        });
      }
      else
      { // Grab the handler and reject it
        dispatch_async(dispatch_get_main_queue(), ^{
          [listener onError:EBBridgeUnknownError message:@"An unknown error has occurred"];
        });
      }
    }
  }
  else
  {
    [_eventDispatcher dispatchEvent:event id:eventID data:data];
  }
}

- (NSString *)getUUID
{
  return [[NSUUID UUID] UUIDString];
}

- (void)emitEvent:(ElectrodeBridgeEvent *)event
{

  NSString *eventID = [self getUUID];
  RCTLogInfo(@"Emitting event[name:%@ id:%@", event.name, eventID);
  
  if (event.dispatchMode == JS || event.dispatchMode == GLOBAL)
  { // Handle JS

    NSDictionary *body = nil;
    if (event.data)
    {
      body = @{EBBridgeMsgID: eventID,
               EBBridgeMsgName: event.name,
               EBBridgeMsgData: event.data};
    }
    else
    {
      body = @{EBBridgeMsgID: eventID,
               EBBridgeMsgName: event.name};
    }

    
    // TODO: Update later to get rid of warning
    [self.bridge.eventDispatcher sendAppEventWithName:EBBridgeEvent
                                                 body:body];
  }
  
  if (event.dispatchMode == NATIVE || event.dispatchMode == GLOBAL)
  { // Handle Native dispatches
    [_eventDispatcher dispatchEvent:event.name id:eventID data:event.data];
  }
}

- (void)sendRequest:(ElectrodeBridgeRequest *)request
 completionListener:(id<ElectrodeRequestCompletionListener>)listener
{
  NSString *requestID = [self getUUID];

  RCTLogInfo(@"Sending request[name:%@ id:%@", request.name, requestID);
  
  // Add the request listener since it could be executed later by JS responding
  [self.requestListeners setObject:listener forKey:requestID];
  
  // Add the timeout handler
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(request.timeout * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    
    // Grab the handler and execute an error, make sure to remove it
    id<ElectrodeRequestCompletionListener> tempListener = [_requestListeners objectForKey:requestID];
    if (tempListener && [tempListener conformsToProtocol:@protocol(ElectrodeRequestCompletionListener)])
    {
      [_requestListeners removeObjectForKey:requestID];
      [tempListener onError:@"EREQUESTTIMEOUT" message:@"Request Timeout"];
    }
  });
  
  // Dispatch to JS or Native depending which was selected
  if (request.dispatchMode == JS)
  {
    NSDictionary *body = nil;
    if (request.data)
    {
      body = @{EBBridgeMsgID: requestID,
               EBBridgeMsgName: request.name,
               EBBridgeMsgData: request.data};
    }
    else
    {
      body = @{EBBridgeMsgID: requestID,
               EBBridgeMsgName: request.name};
    }
    
    // TODO: Update later to get rid of warning
    [self.bridge.eventDispatcher sendAppEventWithName:EBBridgeRequest
                                                 body:body];
  }
  else
  {
    [_requestDispatcher dispatchRequest:request.name id:requestID data:request.data completion:
     ^(NSDictionary *data, NSError *error)
     {
       
       id<ElectrodeRequestCompletionListener> tempListener = [_requestListeners objectForKey:requestID];
       if (tempListener && [tempListener conformsToProtocol:@protocol(ElectrodeRequestCompletionListener)])
       {
         [_requestListeners removeObjectForKey:requestID];
         
         if (!error)
         {
           [listener onSuccess:data];
         }
         else
         {
           [listener onError:error.domain message:error.localizedDescription];
         }
       }
     }];
  }
}

- (ElectrodeEventRegistrar *)eventRegistrar
{
  return self.eventDispatcher.eventRegistrar;
}

- (ElectrodeRequestRegistrar *)requestRegistrar
{
  return self.requestDispatcher.requestRegistrar;
}

- (NSMutableDictionary<NSString *, id<ElectrodeRequestCompletionListener>> *)requestListeners
{
  if (!_requestListeners)
  {
    _requestListeners = [[NSMutableDictionary alloc] init];
  }
  
  return _requestListeners;
}
@end
