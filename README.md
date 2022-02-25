# Keycloak User Management

This is the code used to create Keycloak users using the Keycloak Java library. The code is split into three main sections.

## CSV Lambda
This lambda, defined in the `CSVLambda` class is used to bulk create users from a CSV file. The CSV file is uploaded to an S3 bucket which triggers the lambda.

## API Lambda
This lambda, defined in `ApiLambda` is attached to API Gateway. This API is used by the end to end tests to create and delete Keycloak users to avoid having to open up the Keycloak admin interface to the GitHub actions servers in the US.

## Common Code
The common code is defined in `KeycloakUsers` The structure of the csv file and the structure of the API JSON is defined in the `UserCredentials` class.
```scala
case class UserCredentials(email: String,
                             password: Option[String] = None,
                             firstName: String,
                             lastName: String,
                             body: Option[String] = None,
                             userType: Option[String] = None,
                             sendEmail: Option[Boolean] = None
                            )
```
If the password is not supplied then one is not set. This allows us to create a user with a password for the end to end tests but we won't use it for bulk creation of real users.

Body and user type are optional because they aren't necessary for some users.

Send email can be set to false or left blank. If it is set to true, an account setup email is sent to the user.

## Running the code locally
There is a `LambdaRunner` class which allows you to run the code locally. To run the `CSVLambda` class, you will need credentials to the sandbox account.