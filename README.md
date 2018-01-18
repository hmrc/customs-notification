# customs-notification

 [ ![Download](https://api.bintray.com/packages/hmrc/releases/customs-notification/images/download.svg) ](https://bintray.com/hmrc/releases/customs-notification/_latestVersion)

The objective of the this endpoint is as below: 

1. Receive an update from CDS Backend System (Messaging) regarding a declaration that a CDS Client made earlier using Customs Declarations API

2. Fetch client details(Client’s provided callback URL and Security Token) using the CDS Client ID received in header

3. Notify the CDS Client with the given payload by calling notification gateway service with the payload

## HTTP return codes

| HTTP Status   | Code Error scenario                                                                              |
| ------------- | ------------------------------------------------------------------------------------------------ |
| 204           | If the request is processed successful.                                                          |
| 400           | This status code will be returned in case of incorrect data,incorrect data format, missing parameters etc. are provided in the request. |
| 401           | If request has missing or invalid Authorization header (when configured to check the header).                                            |
| 406           | If request has missing or invalid ACCEPT header.                                                   |
| 415           | If request has missing or invalid Content-Type header.                                             |
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

### Body
The body of the request will contain the XML payload. 

# Configuring `Authorization` header check

To configure the service to accept requests only with specific value in `Authorization` header with `Basic` authentication scheme, the configuration key `auth.token.internal` should be defined with required value.

## Example
Accept only requests having the header `Authorization: Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=`

    auth.token.internal = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="

# Switching service endpoints

Dynamic switching of service endpoints has been implemented for connectors. To configure dynamic
switching of the endpoint there must be a corresponding section in the application config file
(see example below). This should contain the endpoint config details.

## Example
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

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
