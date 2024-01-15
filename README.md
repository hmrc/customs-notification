# Customs Notification

The objective of this service is to receive notifications from the CDS backend and forward them to one of three destinations, depending on the client subscription configuration.

## Types of notifications

The client subscription configuration is queried from [api-subscription-fields](https://github.com/hmrc/api-subscription-fields).

Given a *client subscription ID* (or *csid*), the service will respond with a *client ID*, and potentially a *callback URL* and *security token*.

A particular *client ID* may be associated to more than one *client subscription ID*.

The contents of the response will determine the type and therefore destination of the notification:

| Type          | [api-subscription-fields](https://github.com/hmrc/api-subscription-fields) response condition                                       | Notification send destination                                                        |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| External push | Callback URL and security token exist                                                                                               | [customs-notification-gateway](https://github.com/hmrc/customs-notification-gateway) |
| Internal push | Callback URL and security token exist, and client ID matches [internal clients list in config](#configuration-for-internal-clients) | Direct HTTP POST to callback URL                                                     |
| Pull          | Callback URL does not exist                                                                                                         | [api-notification-pull](https://github.com/hmrc/api-notification-pull)               |

## Retrying failed notifications
A notification that fails to send is retried using a [circuit breaker](https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker) pattern.

Depending on the HTTP response code received,

| State               | HTTP response code | Effect                                                                              | Retry behaviour                                                                                                                                                                                                                       |
|---------------------|--------------------|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Succeeded           | 2xx                | No effect                                                                           | _n/a_                                                                                                                                                                                                                                 |
| FailedButNotBlocked | 3xx or 4xx         | No effect                                                                           | Notification is retried periodically                                                                                                                                                                                                  |
| FailedAndBlocked    | 5xx                | Subsequent incoming notifications are set to *FailedAndBlocked* and csid is blocked | Exactly one notification for that csid is retried periodically. If the subsequent state of the notification is *Succeeded* or *FailedButNotBlocked* then the rest of the notifications for that csid are set to *FailedButNotBlocked* |

The exact delays are detailed in the [Customs Declarations End-to-End Service Guide](https://developer.service.hmrc.gov.uk/guides/customs-declarations-end-to-end-service-guide/documentation/notifications.html#push-notifications).

### Example



<img alt="Failed notification time series diagram" src="diagrams/failed notifications.jpg" title="Diagram showing how blocked CSIDs interact" width="830" />

## Context diagram
<img alt="Context diagram" src="diagrams/context.jpg" title="Context diagram" width="830"/>

## Component diagram
<img alt="Component diagram" src="diagrams/component.png" title="Component diagram" width="830"/>

## Configuration for internal clients

Internal HMRC teams that have applications receiving notifications need to have their client IDs added to the configuration.
This is so that customs-notification-gateway/Squid Proxy can be bypassed and notifications can be sent directly over the internal network. 

The entries are stored under the `internal-client-ids` key in [application.conf](conf/application.conf).

## HTTP return codes

### Notify endpoint codes

| HTTP Status | Scenario                                                                                         |
|-------------|--------------------------------------------------------------------------------------------------|
| 202         | The request is processed successfully.                                                           |
| 400         | The request has incorrect data, incorrect data format, missing parameters etc.                   |
| 401         | The request has a missing or invalid Authorization header (when configured to check the header). |
| 406         | The request has a missing or invalid ACCEPT header.                                              |
| 415         | The request has a missing or invalid Content-Type header.                                        |
| 500         | There is a system error such as a timeout, network error etc.                                    |

### Blocked flag endpoint codes

| HTTP Status   | Scenario                                                                                              |
| ------------- |-------------------------------------------------------------------------------------------------------|
| 200           | If blocked count request is processed successfully                                                    |
| 204           | If remove blocked flags modifies some notifications                                                   |
| 404           | If delete blocked flags request fails to remove any blocked flags.                                    |
| 500           | In case of a system error such as time out, server down etc. ,this HTTP status code will be returned. |
  

## Request Structure

### HTTP headers

| Header            | Mandatory/Optional | Description                                                                 |
| -------------     | -------------------|---------------------------------------------------------------------------- |
| Content-Type      | M                  |Fixed `application/xml; charset=UTF-8`                                       |
| Accept            | M                  |Fixed `application/xml`                                                      |
| Authorization     | depends on config  |Basic authorization token                                                    |
| X-CDS-Client-ID   | M                  |The client id which was passed to Messaging when client submitted the declaration earlier. This must be a type 4 UUID|
| X-Conversation-ID | M                  |This id was passed to Messaging when the declaration was passed onto Messaging earlier. This must be a UUID|
| X-Client-ID       | M                  |The client id used to identify notifications for count and delete blocked flag operations.|
| X-Submitter-Identifier | O             |The submitter identifier which was passed to CDS when the  declaration or inventory linking request was submitted.  The submitter identifier supports the identification of the trader using the CSP service|

### Body
The body of the request will contain the XML payload. 

## Configuring `Authorization` header check

To configure the service to accept requests only with specific value in `Authorization` header with `Basic` authentication scheme, the configuration key `auth.token.internal` should be defined with required value.

### Example
Accept only requests having the header `Authorization: Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=`

    auth.token.internal = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="

## Blocked flag endpoints

### Count blocked flags for a given client id

    curl -X GET http://customs-notification-host/customs-notification/blocked-count -H 'X-Client-ID: AClientId'
    
### Delete blocked flags for a given client id

    curl -X DELETE http://customs-notification-host/customs-notification/blocked-flag -H 'X-Client-ID: AClientId'

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
