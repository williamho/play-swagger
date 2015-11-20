[![Build Status](https://travis-ci.org/iheartradio/play-swagger.svg)](https://travis-ci.org/iheartradio/play-swagger)
[![Coverage Status](https://coveralls.io/repos/iheartradio/play-swagger/badge.svg?branch=master&service=github)](https://coveralls.io/github/iheartradio/play-swagger?branch=master)
[![Stories in Ready](https://badge.waffle.io/iheartradio/play-swagger.svg?label=ready&title=Ready)](http://waffle.io/iheartradio/play-swagger)
# Swagger API spec generator for Play

A library that generates Swagger specs from route files and case class reflection, no code annotation needed.

## Principles in this lib

1. No code pollution (e.g. annotations)
2. DRY (extract as much information from the code as possible)
3. When documenting an endpoint, it should be just Swagger specification that you need to write. You shall not need to learn another API or spec format.

Which translates to

1. Write your [Swagger specification](http://swagger.io/specification/) in your routes files as comments (json or yml)
2. Reference your case classes in your Swagger spec and play-swagger will generate definitions
3. Override anything in either the Swagger spec in comment or the base Swagger spec file (swagger.yml or swagger.json in your conf)

============================
## Day-to-day usage

For installation/get-started, see the next section.

#### A simple example

In a `cards.routes` which is referenced in `routes` as
```
->  /api/cards    cards.Routes
```
You can write the following Swagger spec in a comment (This example is in yml, but json is also supported). The comment has to start and end with `###`.

If you don't write any comment here, the endpoint is still going to be picked up by play-swagger -- the parameters will be included but there will not be any response format.
This allows newly added endpoints to be automatically included in Swagger with some basic information.
```
 ###
 #  summary: create a card
 #  responses:
 #    200:
 #      description: success
 #      schema:
 #        $ref: '#/definitions/com.iheart.api.Protocol.CardCreated'
 ###
 POST      /users/:profileId/contexts/:contextName/cards       controllers.api.Cards.createCard(profileId: Int, contextName: Option[String])

```

Note that everything in the comment is just standard Swagger definition, and it `$ref`s to a case class `CardCreated`, which is defined in a `Protocol` object, and it references another case class `Card`. Here is the source code:
```scala
package com.iheart.api

object Protocol {
  case class CardCreated(card: Card)

  case class Card(id: Int, name: String)
}
```

This will generate the path with summary, tags, parameters and a response with schema defined, which comes from the comments and case class reflection.
It also recursively generates definitions from your case class.
These schemas assumes that you are using a simple `Json.format[CardCreated]` to generate the json response out of this class.
If not, you will have to write the definition yourself in the base Swagger spec and reference it here at the endpoint
(give it a different name than the full package name though, play-swagger will try to generate definitions for any $ref that starts with the domain package name).

The result Swagger specs will look like:
  ![](http://amp-public-share.s3-website-us-east-1.amazonaws.com/shifu/play-swagger-sample.png)


============================
## Get Started

In short you need to create a controller that uses the library to generate the Swagger spec and make it available as an endpoint.
Then you just need to have a Swagger UI instance to consumer that Swagger spec.


#### Step 1
Add play-swagger dependency to your sbt settings
```scala
resolvers += Resolver.jcenterRepo

libraryDependencies +=  "com.iheart" %% "play-swagger" % "0.1.6"
```

#### Step 2
play-swagger is just a library that generates a Swagger spec json for you.
You can do anything you want with that json object (e.g. save it to a file), but the most common usage would be serving it in an endpoint in your Play app.
Here is how:
Add a controller to your Play app that serves the Swagger spec

```scala

class ApiSpecs @Inject() (router: Router, cached: Cached) extends Controller {
  implicit val cl = getClass.getClassLoader

  // The root package of your domain classes, play-swagger will automatically generate definitions when it encounters class references in this package.
  // In our case it would be "com.iheart"
  val domainPackage = "YOUR.DOMAIN.PACKAGE"
  private lazy val generator = SwaggerSpecGenerator(domainPackage)

  // It would be beneficial to cache this endpoint as we do here,
  // but it's not required if you don't expect much traffic.
  def specs = cached("swaggerDef") {
    Action { _ ⇒
      Ok(generator.generate(router.documentation))
    }
  }

}
```

#### Step 3
Add an endpoint to the routes file

```
###
# summary: Swagger definition
# description: for Swagger UI to consume
###
GET   /docs/swagger.json         @controllers.swagger.ApiSpecs.specs

```

#### Step 4
Add a base swagger.yml (or swagger.json) to your resources folder. This one needs to provide all the required fields according to Swagger spec
E.g.
```yml
---
  swagger: "2.0"
  info:
    title: "Poweramp API"
    description: "Power your music"
    version: "1.0.0"
  host: api2.iheart.com
  schemes:
    - "https"
  consumes:
    - application/json
  produces:
    - application/json
```

#### Step 5a
Deploy a Swagger UI and point to the Swagger spec endpoint, or

#### Step 5b
Alternatively you can use swagger-ui webjar and have your Play app serving the Swagger UI:

Add the following dependency
```scala
libraryDependencies += "org.webjars" % "swagger-ui" % "2.1.2"
```

Add the following to your route file
```
### NoDocs ###
GET   /docs/swagger-ui/*file        controllers.Assets.at(path:String="/public/lib/swagger-ui", file:String)

```

Then you should be able to open the Swagger UI at
<http://localhost:9000/docs/swagger-ui/index.html?url=/docs/swagger.json>



============================
## How to contribute

If you have any questions/bug reports, please submit an issue on github.
With good unit test coverage, it's pretty easy to add/modify this library as well.
Code contributions are more than welcome. Make sure that your code is tested and submit the pull request!


===========================
## FAQ

#### How to override?
To override any of the automatically generated fields, you just need to write the same part in your comment or base swagger spec file.

#### How to hide an endpoint?
If you don't want an endpoint to be included, add `### NoDocs ###` in front of it, e.g.
```
### NoDocs ###
GET   /docs/swagger-ui/*file        controllers.Assets.at(path:String="/public/lib/swagger-ui", file:String)
```

#### How to specify body content in a POST endpoint
Body content is specified as a special parameter in swagger. So you need to create a parameter in your swagger spec comment as "body", for example
```
###
#  parameters:
#    - name: body
#      schema:
#        $ref: '#/definitions/com.iheart.api.Track'
###
POST   /tracks       controller.Api.createTrack()
```
Again, play-swagger will generate the definition for com.iheart.api.Track case class

#### How do I use a different "host" for different environment?
The library returns play JsObject, you can change however you want like
```scala
val spec = ps.generate(routeDocuments) + ("host" -> JsString(myHost))
```


#### How to find more examples?
In the tests!
[/test/scala/com.iheart.playSwagger/SwaggerSpecGeneratorSpec.scala](https://github.com/iheartradio/play-swagger/blob/master/src/test/scala/com/iheart/playSwagger/SwaggerSpecGeneratorSpec.scala)

