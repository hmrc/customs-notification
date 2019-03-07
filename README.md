# customs-notification

The objective of this service is 

1. Receive an update from CDS Backend System (Messaging) regarding a declaration that a CDS Client made earlier using Customs Declarations API

2. Fetch client details(Client’s provided callback URL and Security Token) using the CDS Client ID received in header

3. Notify the CDS Client with the given payload by calling the notification gateway service with the payload or sending it to the pull queue

4. Provide two endpoints for the counting and deleting of blocked flags which prevents notifications from being pushed.  

## Configuration for Internal Clients

Internal HMRC teams that have applications that receive Notifications need to have their client Ids added to the configuration.
This is so that customs-notification-gateway/Squid Proxy can be bypassed and the Notifications sent directly over the internal network. 
The entries should be in the following format:
 
    push.internal.clientIds.0 = "ClientIdOne"
    push.internal.clientIds.1 = "ClientIdTwo"
  

## HTTP return codes

### Notify endpoint codes

| HTTP Status   | Error code scenario                                                                                |
| ------------- | ---------------------------------------------------------------------------------------------------|
| 202           | If request is processed successfully.                                                              |
| 400           | If request has incorrect data, incorrect data format, missing parameters etc.                      |
| 401           | If request has missing or invalid Authorization header (when configured to check the header).      |
| 406           | If request has missing or invalid ACCEPT header.                                                   |
| 415           | If request has missing or invalid Content-Type header.                                             |
| 500           | In case of a system error such as time out, server down etc. ,this HTTP status code will be returned.|

### Blocked flag endpoint codes

| HTTP Status   | Error code scenario                                                                                |
| ------------- | ---------------------------------------------------------------------------------------------------|
| 200           | If blocked count request is processed successfully                                                 |
| 204           | If remove blocked flags modifies some notifications                                                |
| 404           | If delete blocked flags request fails to remove any blocked flags.                                 |
| 500           | In case of a system error such as time out, server down etc. ,this HTTP status code will be returned.|
  

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

## Switching service endpoints

Dynamic switching of service endpoints has been implemented for connectors. To configure dynamic
switching of the endpoint there must be a corresponding section in the application config file
(see example below). This should contain the endpoint config details.

### Example
The service `api-subscription-fields` has a `default` configuration and a `stub` configuration. Note
that `default` configuration is declared directly inside the `api-subscription-fields` section.

    {
        ...
        services {
          ...

          api-subscription-fields {
            host = localhost
            port = 9650
            context = /field
    
            stub {
              host = localhost
              port = 9477
              context = /api-subscription-fields/fields
            }
          }
        }
    }

## Set stub configuration for service

### Request

    curl -X "POST" http://customs-notification-host/test-only/service/api-subscription-fields/configuration -H 'content-type: application/json' -d '{ "environment": "stub" }'

### Response

    The service api-subscription-fields is now configured to use the stub environment

## Set default configuration for service

### Request

    curl -X "POST" http://customs-notification-host/test-only/service/api-subscription-fields/configuration -H 'content-type: application/json' -d '{ "environment": "default" }'

### Response

    The service api-subscription-fields is now configured to use the default environment

## Get the current configuration for a service

### REQUEST

    curl -X "GET" http://customs-notification-host/test-only/service/api-subscription-fields/configuration

### RESPONSE

    {
      "service": "api-subscription-fields",
      "environment": "stub",
      "url": "http://currenturl/api-subscription-fields"
      "bearerToken": "current token"
    }


## Scheduled Email
A warning email is sent when the scheduler finds notifications for configurable clientIds (`pull.exclude.csIds`) in the database that are more than `pull.exclude.older.milliseconds` plus `push.polling.delay.duration.milliseconds` old.
The check is currently configured (`pull.exclude.email.interval.duration.minutes`) to run every 30 minutes. The set of email "to" addresses are configured with `pull.exclude.email.addresses`.


## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
