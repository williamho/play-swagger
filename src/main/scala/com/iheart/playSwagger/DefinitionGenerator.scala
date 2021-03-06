package com.iheart.playSwagger

import com.iheart.playSwagger.Domain.{SwaggerParameter, Definition}
import org.joda.time.DateTime

import scala.reflect.runtime.universe._
import scala.reflect.api

import SwaggerParameterMapper.mapParam

case class DefinitionGenerator(domainNameSpace: Option[String] = None)(implicit cl: ClassLoader) {

  def definition(tpe: Type): Definition = {
    val fields = tpe.decls.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor ⇒ m
    }.get.paramLists.head

    val properties = fields.map { field ⇒
      mapParam(field.name.decodedName.toString, field.typeSignature.dealias.toString, domainNameSpace)  //todo: find a better way to get the string representation of typeSignature
    }

    Definition( name = tpe.typeSymbol.fullName,
                properties = properties)
  }

  def definition[T: TypeTag]: Definition = definition(weakTypeOf[T])


  def definition(className: String): Definition = {
    val mirror = runtimeMirror(cl)
    val sym = mirror.staticClass(className)
    val tpe = sym.selfType  
    definition(tpe)
  }

  def allDefinitions(typeNames: List[String]): List[Definition] = {

    def allRefferdDefs(defName: String, memo: List[Definition]): List[Definition] = {
      memo.find(_.name == defName) match {
        case Some(_) ⇒ memo
        case None ⇒
          val thisDef = definition(defName)
          val refNames = thisDef.properties.collect {
            case p if p.referenceType.isDefined ⇒ p.referenceType.get
            case p if p.items.isDefined ⇒ p.items.get
          }
          refNames.foldLeft(thisDef :: memo) { (foundDefs, refName) =>
            allRefferdDefs(refName, foundDefs)
          }
      }
    }

    typeNames.foldLeft(List.empty[Definition]) { (memo, typeName) ⇒
      allRefferdDefs(typeName, memo)
    }
  }
}


object DefinitionGenerator {
  def apply(domainNameSpace: String)(implicit cl: ClassLoader): DefinitionGenerator = DefinitionGenerator(Some(domainNameSpace))
}
