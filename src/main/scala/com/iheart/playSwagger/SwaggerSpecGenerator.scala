package com.iheart.playSwagger

import com.fasterxml.jackson.databind.ObjectMapper
import com.iheart.playSwagger.Domain.{Definition, SwaggerParameter}
import play.api.libs.json._
import ResourceReader.read
import play.api.libs.functional.syntax._
import org.yaml.snakeyaml.Yaml

import SwaggerParameterMapper.mapParam

object SwaggerSpecGenerator {
  private val marker = "###"
  def apply(domainNameSpace: String)(implicit cl: ClassLoader): SwaggerSpecGenerator = SwaggerSpecGenerator(Some(domainNameSpace))
}

case class SwaggerSpecGenerator(domainNameSpace: Option[String] = None, defaultPostBodyFormat: String = "application/json")(implicit cl: ClassLoader) {

  import SwaggerSpecGenerator.marker

  private implicit def toOWrites[A](writes: Writes[A]): OWrites[A] = OWrites {
    writes.writes(_).asInstanceOf[JsObject]
  }

  private val referencePrefix = "#/definitions/"

  private def referenceWrites(path: String, extraFields: JsObject = Json.obj() ) = OWrites((os: Option[String]) ⇒
    os.fold(Json.obj())(v ⇒
      Json.obj(path → Json.obj("$ref" → JsString(referencePrefix + v)))
      ++ extraFields
    ))

  private val propFormat: Writes[SwaggerParameter] = (
      (__ \ 'name ).write[String] ~
      (__ \ 'type ).writeNullable[String] ~
      (__ \ 'format ).writeNullable[String] ~
      (__ \ 'required ).write[Boolean] ~
      referenceWrites("schema") ~
      referenceWrites("items", Json.obj("type" → "array"))
    )(unlift(SwaggerParameter.unapply))

  private implicit val propFormatInDef = propFormat.transform((__ \ 'name).prune(_).get)

  private implicit val swesWriter = Writes[Seq[SwaggerParameter]] { ps ⇒
    JsObject(ps.map(p ⇒ p.name → Json.toJson(p)))
  }
  private implicit val defFormat: Writes[Definition] = (
      (__ \ 'description ).writeNullable[String] ~
      (__ \ 'properties ).write[Seq[SwaggerParameter]] ~
      (__ \ 'required ).write[Seq[String]]
    )((d: Definition) ⇒ (d.description, d.properties, d.properties.filter(_.required).map(_.name)))

  def generate(routesDocumentation: RoutesDocumentation): JsValue = {
    generateWithBase(routesDocumentation, RoutesFileReader().readAll(), base)
  }

  private def base = readBaseCfg("swagger.json") orElse readBaseCfg("swagger.yml") getOrElse Json.obj()

  private[playSwagger] def generateWithBase(routesDocumentation: RoutesDocumentation,
                                            routesLines: Map[Tag, List[Line]],
                                            baseJson: JsObject = Json.obj()) : JsObject = {

    val pathsJson = routesLines.map { case (tag, lines) ⇒
      val subTag = if(tag == RoutesFileReader.rootRoute) None else Some(tag)
      paths(routesDocumentation, lines, subTag)
    }.reduce(_ ++ _)
    val allRefs = (pathsJson ++ baseJson) \\ "$ref"
    val definitions = DefinitionGenerator(domainNameSpace).allDefinitions(allRefs.
      map(_.asOpt[String]).collect{ case Some(s) ⇒ s }.
      filter(s ⇒ domainNameSpace.fold(false)(ns ⇒ s.startsWith(referencePrefix + ns))).
      map(_.drop(referencePrefix.length))
      toList)

    val definitionsJson = JsObject(definitions.map(d ⇒ d.name → Json.toJson(d)))
    val generatedTagsJson = JsArray(routesLines.keys.filterNot(_ == RoutesFileReader.rootRoute).map(t ⇒ Json.obj("name" → t)).toSeq)
    val tagsJson = mergeByName(generatedTagsJson, (baseJson \ "tags").asOpt[JsArray].getOrElse(JsArray()))
    Json.obj("paths" → pathsJson, "definitions" → definitionsJson) deepMerge baseJson + ("tags" → tagsJson)
  }

  private def mergeByName(base: JsArray, toMerge: JsArray): JsArray = {
    JsArray(base.value.map { bs ⇒
      val name = (bs \ "name").as[String]
      findByName(toMerge, name).fold(bs) { f ⇒ bs.asInstanceOf[JsObject] deepMerge f }
    } ++ toMerge.value.filter { tm ⇒
      val name = (tm \ "name").as[String]
      findByName(base, name).isEmpty
    })
  }


  private def findByName(array: JsArray, name: String): Option[JsObject] =
    array.value.find(param ⇒ (param \ "name").asOpt[String] == Some(name)).map(_.as[JsObject])


  private def readBaseCfg(name: String): Option[JsObject] = {
    Option(cl.getResource(name)).map { url ⇒
      val st = url.openStream()
      try {
        val ext = url.getFile.split("\\.").last
        ext match {
          case "json" ⇒ Json.parse(st).asInstanceOf[JsObject]
          case "yml" ⇒ parseYaml(read(st).mkString("\n"))
          case unknown ⇒ throw new IllegalArgumentException(s"$name has an unsupported extension. Use either json or yaml. " )
        }
      } finally {
        st.close()
      }
    }
  }

  private def parseYaml(yamlStr: String): JsObject = {
    val yaml = new Yaml()
    val map = yaml.load(yamlStr).asInstanceOf[java.util.Map[String, Object]]
    val mapper = new ObjectMapper()
    val jsonString = mapper.writeValueAsString(map)
    Json.parse(jsonString).asInstanceOf[JsObject]
  }


  private[playSwagger] def paths(routesDocumentation: RoutesDocumentation, routesLines: List[String], tag: Option[Tag]): JsObject = {
    val allRoutes = routesLines.map(_.trim).filterNot(_.isEmpty)

    def tryParseYaml(comment: String): Option[JsObject] = {
      val pattern = "^\\w+:".r
      pattern.findFirstIn(comment).map(_ ⇒ parseYaml(comment))
    }

    def tryParseJson(comment: String): Option[JsObject] = {
      if(comment.startsWith("{"))
        Some(Json.parse(comment).asInstanceOf[JsObject])
      else None
    }

    def endPointSpec(controllerDesc: String, commentLines: List[String], path: String): JsObject = {

      def amendBodyParam(params: JsArray): JsArray = {
        val bodyParam = findByName(params, "body")
        if(bodyParam.isDefined) {
          val enhancedBodyParam = bodyParam.get + ("in" → JsString("body"))
          JsArray(enhancedBodyParam +: params.value.filterNot(_ == bodyParam.get))
        } else params
      }

      val commentDocLines = commentLines match {
        case `marker` +: docs :+ `marker` ⇒ docs
        case _ ⇒ Nil
      }

      val paramsFromController = {
        val paramsInPath = """\{(\w+)\}""".r.findAllMatchIn(path).map(_.group(1))

        val paramsPattern = "\\((.+)\\)$".r

        JsArray(paramsPattern.findFirstMatchIn(controllerDesc).map(_.group(1)).fold(Array[SwaggerParameter]()) { paramsString ⇒
          paramsString.split(",").map { param =>
            val Array(name, pType) = param.split(":")
            mapParam(name, pType, domainNameSpace)
          }
        }.map { p ⇒
          val jo = Json.toJson(p)(propFormat).asInstanceOf[JsObject]
          val in = if (paramsInPath.contains(p.name)) "path" else "query"
          jo + ("in" → JsString(in))
        })
      }

      val jsonFromComment = for {
        leadingSpace ← commentDocLines.headOption.flatMap( """^(#\s*)""".r.findFirstIn )
        comment  = commentDocLines.map(_.drop(leadingSpace.length)).mkString("\n")
        result ← tryParseJson(comment) orElse tryParseYaml(comment)
      } yield result


      val paramsFromComment = jsonFromComment.flatMap(jc ⇒ (jc \ "parameters").asOpt[JsArray]).map(amendBodyParam)

      val mergedParams = mergeByName(paramsFromController, paramsFromComment.getOrElse(JsArray()))

      val parameterJson = (if (!mergedParams.value.isEmpty) Json.obj("parameters" → mergedParams) else Json.obj())

      val rawPathJson = tag.fold(Json.obj())( t ⇒ Json.obj("tags" → List(t))) ++ jsonFromComment.getOrElse(Json.obj()) ++ parameterJson

      val hasConsumes = (rawPathJson \ "consumes").toOption.isDefined

      if (findByName(mergedParams, "body").isDefined && !hasConsumes)
        rawPathJson + ("consumes" → Json.arr(defaultPostBodyFormat))
      else rawPathJson
    }


    def endPointEntry(routeDocumentation: (String, String, String)): Option[(String, JsValue)] = {
      def methodDesc(raw: String) = raw.replace(" ", "").replace("@", "")
      def methodPath(desc: String) = """(controllers[^\(]+)(\(.*\))?$""".r.findFirstMatchIn(desc).map(_.group(1))

      val (method, rawPath, controllerRaw) = routeDocumentation

      val controllerDesc = methodDesc(controllerRaw)
      val controllerMethodPath = methodPath(controllerDesc).get

      val beforeRouteEntry = allRoutes.takeWhile { l =>
        methodPath(methodDesc(l)).fold(true)(_ != controllerMethodPath)
      }

      val commentLines = beforeRouteEntry.reverse.takeWhile(line ⇒ line.startsWith("#")).reverse

      if(beforeRouteEntry.length == allRoutes.length)
        None //didn't find it in the routes lines
      else if(s"${marker}\\s*NoDocs\\s*${marker}".r.findFirstIn(commentLines.mkString("\n")).isDefined)
        None
      else {
        val path = rawPath.replaceAll( """\$(\w+)<[^>]+>""", "{$1}")
        Some(path → Json.obj(method.toLowerCase -> endPointSpec(controllerDesc, commentLines, path)))
      }
    }

    JsObject(routesDocumentation.map(endPointEntry).collect { case Some(o) ⇒ o })
  }
}




