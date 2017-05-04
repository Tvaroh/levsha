package levsha

import levsha.Change.DiffTestChangesPerformer
import levsha.impl.DiffRenderContext
import levsha.impl.DiffRenderContext.{ChangesPerformer, DummyChangesPerformer}
import org.scalacheck.{Gen, Properties}
import org.scalacheck._

import scala.collection.mutable

/**
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
object DiffProperties extends Properties("Diff") {

  import Document._

  property("doesn't fail on arbitrary trees") = {
    val gen = for (a <- genDocument; b <- genDocument)
      yield (a, b)
    Prop.forAll(gen) {
      case (a, b) =>
        val index = mutable.Map.empty[Int, String]
        val dummyPerformer = new DummyChangesPerformer()
        val rcFirst = new DiffRenderContext[Nothing](identIndex = index)
        val rcSecond = new DiffRenderContext[Nothing](identIndex = index)
        a(rcFirst)
        b(rcSecond)
        rcSecond.diff(rcFirst, dummyPerformer)
        true
    }
  }

  property("generate valid changes set") = {
    Prop.forAll(ChangesTrial.genChangesTrial) { trial =>
      val index = mutable.Map.empty[Int, String]
      val performer = new DiffTestChangesPerformer()
      val rcFirst = new DiffRenderContext[Nothing](identIndex = index)
      val rcSecond = new DiffRenderContext[Nothing](identIndex = index)
      trial.originalDocument(rcFirst)
      trial.newDocument(rcSecond)
      rcSecond.diff(rcFirst, performer)
      println(performer.result)
      performer.result == trial.changes
    }
  }

}

sealed trait Document {
  def apply(rc: RenderContext[Nothing]): Unit = this match {
    case Document.Text(text) => rc.addTextNode(text)
    case Document.Element(name, attrs, xs) =>
      rc.openNode(name)
      attrs foreach {
        case (attr, value) =>
          rc.setAttr(attr, value)
      }
      xs.foreach(x => x(rc))
      rc.closeNode(name)
  }
}

object Document {

  case class Text(value: String) extends Document
  case class Element(name: String, attrs: Map[String, String], xs: Seq[Document]) extends Document

  val genAttr = {
    Gen.frequency(
      (5, "class" -> "hello"),
      (7, "class" -> "world"),
      (1, "name" -> "cow"),
      (2, "lang" -> "ru"),
      (2, "lang" -> "ru"),
      (1, "style" -> "margin: 10;"),
      (1, "style" -> "padding: 10;")
    )
  }

  val genAttrs = {
    Gen.choose(0, 3) flatMap { s =>
      Gen.listOfN(s, genAttr).map(_.toMap)
    }
  }

  val genTag = {
    Gen.frequency(
      (8, "div"),
      (4, "span"),
      (1, "p"),
      (1, "input"),
      (1, "button")
    )
  }

  val genText = {
    Gen.nonEmptyListOf(Gen.alphaChar).map(s => Text(s.mkString))
  }

  val genElement = Gen.sized { size =>
    for {
      name <- genTag
      attrs <- genAttrs
      len <- Gen.choose(0, size)
      gen = Gen.resize(size / (len + 1), genDocument)
      xs <- Gen.listOfN(len, gen)
    } yield {
      Element(name, attrs, xs)
    }
  }

  def genDocument: Gen[Document] = Gen.lzy {
    Gen.oneOf(genText, genElement)
  }
}

sealed trait Change {
  def id: String
}

object Change {

  final class DiffTestChangesPerformer extends ChangesPerformer {
    private val buffer = mutable.Buffer.empty[Change]
    def removeAttr(id: String, name: String): Unit =
      buffer += Change.removeAttr(id, name)
    def remove(id: String): Unit =
      buffer += Change.remove(id)
    def setAttr(id: String, name: String, value: String): Unit =
      buffer += Change.setAttr(id, name, value)
    def createText(id: String, text: String): Unit =
      buffer += Change.createText(id, text)
    def create(id: String, tag: String): Unit =
      buffer += Change.create(id, tag)
    def result: Seq[Change] = buffer
  }

  case class removeAttr(id: String, name: String) extends Change
  case class remove(id: String) extends Change
  case class setAttr(id: String, name: String, value: String) extends Change
  case class createText(id: String, text: String) extends Change
  case class create(id: String, tag: String) extends Change

//  def transformDocument(document: Document, changeSet: Seq[Change]): Document = {
//    import Document._
//    val changeMap = changeSet.map(x => x.id -> x).toMap
//    def aux(id: String, acc: List[String], document: Document): Document = (document, changeMap.get(id)) match {
//      case (_: Element, Some(Change.create(_, tag))) if !acc.contains(id) =>
//        aux(id, id :: acc, Element(tag, Map.empty, Nil))
//    }
//  }
}

case class ChangesTrial(
  originalDocument: Document,
  newDocument: Document,
  changes: Seq[Change]
) {
  override def toString: String = {
    def attrsToString(attrs: Map[String, String]) = attrs
      .map { case (name, value) => s"""$name="$value"""" }
      .mkString(" ")

    def docToString(level: String, doc: Document): String = {
      doc match {
        case Document.Text(value) => s"$level'$value'\n"
        case Document.Element(name, attrs, Nil) =>
          s"$level<$name ${attrsToString(attrs)} />\n"
        case Document.Element(name, attrs, xs) =>
          val children = xs.map(docToString(level + "  ", _)).mkString
          s"$level<$name ${attrsToString(attrs)}>\n$children$level</$name>\n"
      }
    }
    s"""Changes Trial
       |-------------
       |Original Document:
       |${docToString("  ", originalDocument)}
       |
       |New Document:
       |${docToString("  ", newDocument)}
       |
       |Changes:
       |${changes.mkString("\n")}
     """.stripMargin
  }
}

object ChangesTrial {

  import Document._

  type FlatDoc = List[(String, Document)]

  private def makeFlat(id: String, d: Document): FlatDoc = d match {
    case t: Text => List(id -> t)
    case el: Element  =>
      (id, el.copy(xs = Nil)) :: {
        el.xs.toList.zipWithIndex flatMap { case (child, i) =>
          makeFlat(s"${id}_$i", child)
        }
      }
  }

  private def makeUnflat(flatDoc: FlatDoc) = {
    val parsed = flatDoc.map { case (id, doc) => id.split('_').toList.map(_.toInt) -> doc }
    val (1 :: Nil, root) :: children = parsed.sortBy(_._1.toIterable)
    def findChildren(id: List[Int]): List[Document] = children collect {
      case (childId, e: Element) if childId.length == id.length + 1 && childId.startsWith(id) =>
        e.copy(xs = findChildren(childId))
    }
    root match {
      case _: Text => root
      case e: Element => e.copy(xs = findChildren(List(1)))
    }
  }

  sealed trait Intent {
    def id: String
  }

  object Intent {

    case class SetAttr(id: String, attr: String, value: String) extends Intent
    case class RemoveAttr(id: String, attr: String) extends Intent
    case class Append(id: String, doc: Document) extends Intent
    case class Delete(id: String) extends Intent
    case class Replace(id: String, doc: Document) extends Intent

    def genSetAttrIntent(id: String): Gen[SetAttr] = Document.genAttr map { case (attr, value) => SetAttr(id, attr, value) }
    def genRemoveAttrIntent(id: String): Gen[RemoveAttr] = Document.genAttr map { case (attr, _) => RemoveAttr(id, attr) }
    def genAppendIntent(id: String): Gen[Append] = Document.genDocument map { doc => Append(id, doc) }
    def genReplaceIntent(id: String): Gen[Replace] = Document.genDocument map { doc => Replace(id, doc) }

    def genIntents(flatDoc: FlatDoc): Gen[Seq[Intent]] = {
      for {
        intentNum <- Gen.choose(1, flatDoc.length)
        intentGens = (0 until intentNum) map { _ =>
          for {
            index <- Gen.choose(0, flatDoc.length - 1)
            (id, doc) = flatDoc(index)
            intentGen <- doc match {
              case _: Text => Gen.oneOf(
                genAppendIntent(id),
                genReplaceIntent(id)
              )
              case el: Element => Gen.oneOf(
                genSetAttrIntent(id).filter(setAttr => !el.attrs.contains(setAttr.attr)),
                genRemoveAttrIntent(id).filter(rmAttr => el.attrs.contains(rmAttr.attr)),
                genAppendIntent(id),
                genReplaceIntent(id).filter {
                  case Intent.Replace(_, _: Text) => true
                  case Intent.Replace(_, elToReplace: Element) => elToReplace.name != el.name
                }
                // TODO check delete
              )
            }
          } yield intentGen
        }
        intents <- Gen.sequence(intentGens)
      } yield {
        import collection.JavaConverters._
        val xs = intents.asScala
        xs.foldLeft(List.empty[Intent]) {
          case (acc, intent) if !acc.exists(x => x.id.startsWith(intent.id)) =>
            intent :: acc
          case (acc, _) => acc
        }
      }
    }
  }

  val genChangesTrial = {
    def flatDocToChanges(doc: (String, Document)) = doc match {
      case (id, Text(value)) => List(Change.createText(id, value))
      case (id, Element(name, attrs, _)) =>
        Change.create(id, name) :: attrs.toList.map {
          case (attr, value) => Change.setAttr(id, attr, value)
        }
    }
    for {
      originalDocument <- genDocument
      flatDocument = makeFlat("1", originalDocument)
      intents <- Intent.genIntents(flatDocument)
    } yield {
      val changes = intents flatMap {
        case Intent.Replace(id, doc) => makeFlat(id, doc).flatMap(flatDocToChanges)
        case Intent.Append(id, doc) =>
          // TODO
          List() //makeFlat(id, doc).flatMap(flatDocToChanges)
        case Intent.Delete(id) => List(Change.remove(id))
        case Intent.SetAttr(id, attr, value) => List(Change.setAttr(id, attr, value))
        case Intent.RemoveAttr(id, attr) => List(Change.removeAttr(id, attr))
      }
      val updatedFlatDocument = changes.foldLeft(flatDocument.toMap) {
        case (acc, Change.create(id, name)) => acc + (id -> Element(name, Map.empty, Nil))
        case (acc, Change.createText(id, text)) => acc + (id -> Text(text))
        case (acc, Change.remove(id)) => acc - id
        case (acc, Change.removeAttr(id, attr)) if acc.contains(id) => acc(id) match {
          case _: Text => acc
          case el: Element =>
            val updatedEl = el.copy(attrs = el.attrs - attr)
            acc + (id -> updatedEl)
        }
        case (acc, Change.setAttr(id, name, value)) if acc.contains(id) => acc(id) match {
          case _: Text => acc
          case el: Element =>
            val updatedEl = el.copy(attrs = el.attrs + (name -> value))
            acc + (id -> updatedEl)
        }
      }
      ChangesTrial(
        originalDocument,
        makeUnflat(updatedFlatDocument.toList),
        changes
      )
    }
  }
}
