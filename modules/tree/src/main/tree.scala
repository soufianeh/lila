package lila.tree

import chess.Centis
import chess.format.pgn.{ Glyph, Glyphs }
import chess.format.{ FEN, Uci, UciCharPair }
import chess.opening.FullOpening
import chess.Pos
import chess.variant.Crazyhouse
import play.api.libs.json.*

import lila.common.Json.*

sealed trait Node:
  def ply: Int
  def fen: FEN
  def check: Boolean
  // None when not computed yet
  def dests: Option[Map[Pos, List[Pos]]]
  def drops: Option[List[Pos]]
  def eval: Option[Eval]
  def shapes: Node.Shapes
  def comments: Node.Comments
  def gamebook: Option[Node.Gamebook]
  def glyphs: Glyphs
  def children: List[Branch]
  def opening: Option[FullOpening]
  def comp: Boolean // generated by a computer analysis
  def crazyData: Option[Crazyhouse.Data]
  def addChild(branch: Branch): Node
  def dropFirstChild: Node
  def clock: Option[Centis]
  def forceVariation: Boolean

  // implementation dependent
  def idOption: Option[UciCharPair]
  def moveOption: Option[Uci.WithSan]

  // who's color plays next
  def color = chess.Color.fromPly(ply)

  def mainlineNodeList: List[Node] =
    dropFirstChild :: children.headOption.fold(List.empty[Node])(_.mainlineNodeList)

case class Root(
    ply: Int,
    fen: FEN,
    check: Boolean,
    // None when not computed yet
    dests: Option[Map[Pos, List[Pos]]] = None,
    drops: Option[List[Pos]] = None,
    eval: Option[Eval] = None,
    shapes: Node.Shapes = Node.Shapes(Nil),
    comments: Node.Comments = Node.Comments(Nil),
    gamebook: Option[Node.Gamebook] = None,
    glyphs: Glyphs = Glyphs.empty,
    children: List[Branch] = Nil,
    opening: Option[FullOpening] = None,
    clock: Option[Centis] = None, // clock state at game start, assumed same for both players
    crazyData: Option[Crazyhouse.Data]
) extends Node:

  def idOption       = None
  def moveOption     = None
  def comp           = false
  def forceVariation = false

  def addChild(branch: Branch)     = copy(children = children :+ branch)
  def prependChild(branch: Branch) = copy(children = branch :: children)
  def dropFirstChild               = copy(children = if (children.isEmpty) children else children.tail)

case class Branch(
    id: UciCharPair,
    ply: Int,
    move: Uci.WithSan,
    fen: FEN,
    check: Boolean,
    // None when not computed yet
    dests: Option[Map[Pos, List[Pos]]] = None,
    drops: Option[List[Pos]] = None,
    eval: Option[Eval] = None,
    shapes: Node.Shapes = Node.Shapes(Nil),
    comments: Node.Comments = Node.Comments(Nil),
    gamebook: Option[Node.Gamebook] = None,
    glyphs: Glyphs = Glyphs.empty,
    children: List[Branch] = Nil,
    opening: Option[FullOpening] = None,
    comp: Boolean = false,
    clock: Option[Centis] = None, // clock state after the move is played, and the increment applied
    crazyData: Option[Crazyhouse.Data],
    forceVariation: Boolean = false // cannot be mainline
) extends Node:

  def idOption   = Some(id)
  def moveOption = Some(move)

  def addChild(branch: Branch): Branch = copy(children = children :+ branch)
  def prependChild(branch: Branch)     = copy(children = branch :: children)
  def dropFirstChild                   = copy(children = if (children.isEmpty) children else children.tail)

  def setComp = copy(comp = true)

object Node:

  enum Shape:
    case Circle(brush: Shape.Brush, orig: Pos)
    case Arrow(brush: Shape.Brush, orig: Pos, dest: Pos)
  object Shape:
    type Brush = String

  opaque type Shapes = List[Shape]
  object Shapes extends TotalWrapper[Shapes, List[Shape]]:
    extension (a: Shapes) def ++(shapes: Shapes): Shapes = (a.value ::: shapes.value).distinct
    val empty: Shapes                                    = Nil

  case class Comment(id: Comment.Id, text: Comment.Text, by: Comment.Author):
    def removeMeta = text.removeMeta map { t => copy(text = t) }
  object Comment:
    opaque type Id = String
    object Id extends OpaqueString[Id]:
      def make = Id(lila.common.ThreadLocalRandom nextString 4)
    private val metaReg = """\[%[^\]]+\]""".r
    opaque type Text = String
    object Text extends OpaqueString[Text]:
      extension (a: Text)
        def removeMeta: Option[Text] =
          val v = metaReg.replaceAllIn(a.value, "").trim
          v.nonEmpty option Text(v)
    enum Author:
      case User(id: UserId, titleName: String)
      case External(name: String)
      case Lichess
      case Unknown
    def sanitize(text: String) = Text {
      lila.common.String
        .softCleanUp(text)
        .take(4000)
        .replaceAll("""\r\n""", "\n") // these 3 lines dedup white spaces and new lines
        .replaceAll("""(?m)(^ *| +(?= |$))""", "")
        .replaceAll("""(?m)^$([\n]+?)(^$[\n]+?^)+""", "$1")
        .replaceAll("[{}]", "") // {} are reserved in PGN comments
    }
  opaque type Comments = List[Comment]
  object Comments extends TotalWrapper[Comments, List[Comment]]:
    extension (a: Comments)
      def findBy(author: Comment.Author) = a.value.find(_.by == author)
      def set(comment: Comment): Comments = {
        if (a.value.exists(_.by == comment.by)) a.value.map {
          case c if c.by == comment.by => c.copy(text = comment.text)
          case c                       => c
        }
        else a.value :+ comment
      }
      def delete(commentId: Comment.Id): Comments = a.value.filterNot(_.id == commentId)
      def +(comment: Comment): Comments           = comment :: a.value
      def ++(comments: Comments): Comments        = a.value ::: comments.value
      def filterEmpty: Comments                   = a.value.filter(_.text.value.nonEmpty)
      def hasLichessComment                       = a.value.exists(_.by == Comment.Author.Lichess)
    val empty = Comments(Nil)

  case class Gamebook(deviation: Option[String], hint: Option[String]):
    private def trimOrNone(txt: Option[String]) = txt.map(_.trim).filter(_.nonEmpty)
    def cleanUp =
      copy(
        deviation = trimOrNone(deviation),
        hint = trimOrNone(hint)
      )
    def nonEmpty = deviation.nonEmpty || hint.nonEmpty

  // TODO copied from lila.game
  // put all that shit somewhere else
  implicit private val crazyhousePocketWriter: OWrites[Crazyhouse.Pocket] = OWrites { v =>
    JsObject(
      Crazyhouse.storableRoles.flatMap { role =>
        Some(v.roles.count(role ==)).filter(0 <).map { count =>
          role.name -> JsNumber(count)
        }
      }
    )
  }
  implicit private val crazyhouseDataWriter: OWrites[chess.variant.Crazyhouse.Data] = OWrites { v =>
    Json.obj("pockets" -> List(v.pockets.white, v.pockets.black))
  }

  implicit val openingWriter: OWrites[chess.opening.FullOpening] = OWrites { o =>
    Json.obj(
      "eco"  -> o.eco,
      "name" -> o.name
    )
  }

  implicit private val posWrites: Writes[Pos] = Writes[Pos] { p =>
    JsString(p.key)
  }
  private val shapeCircleWrites = Json.writes[Shape.Circle]
  private val shapeArrowWrites  = Json.writes[Shape.Arrow]
  given shapeWrites: Writes[Shape] = Writes[Shape] {
    case s: Shape.Circle => shapeCircleWrites writes s
    case s: Shape.Arrow  => shapeArrowWrites writes s
  }
  given Writes[Node.Shapes] = Writes[Node.Shapes] { s =>
    JsArray(s.value.map(shapeWrites.writes))
  }
  given Writes[Glyph] = Json.writes[Glyph]
  given Writes[Glyphs] = Writes[Glyphs] { gs =>
    Json.toJson(gs.toList)
  }

  given Writes[Centis] = Writes { clock =>
    JsNumber(clock.centis)
  }
  given Writes[Comment.Id] = Writes { id =>
    JsString(id.value)
  }
  given Writes[Comment.Text] = Writes { text =>
    JsString(text.value)
  }
  given Writes[Comment.Author] = Writes[Comment.Author] {
    case Comment.Author.User(id, name) => Json.obj("id" -> id.value, "name" -> name)
    case Comment.Author.External(name) => JsString(s"${name.trim}")
    case Comment.Author.Lichess        => JsString("lichess")
    case Comment.Author.Unknown        => JsNull
  }
  given Writes[Node.Comment]  = Json.writes[Node.Comment]
  given Writes[Node.Gamebook] = Json.writes[Node.Gamebook]

  import lila.common.Json.given
  import JsonHandlers.given

  given defaultNodeJsonWriter: Writes[Node] = makeNodeJsonWriter(alwaysChildren = true)

  val minimalNodeJsonWriter = makeNodeJsonWriter(alwaysChildren = false)

  def nodeListJsonWriter(alwaysChildren: Boolean): Writes[List[Node]] =
    Writes[List[Node]] { list =>
      val writer = if (alwaysChildren) defaultNodeJsonWriter else minimalNodeJsonWriter
      JsArray(list map writer.writes)
    }

  def makeNodeJsonWriter(alwaysChildren: Boolean): Writes[Node] =
    Writes { node =>
      import node.*
      try
        val comments = node.comments.value.flatMap(_.removeMeta)
        Json
          .obj(
            "ply" -> ply,
            "fen" -> fen
          )
          .add("id", idOption.map(_.toString))
          .add("uci", moveOption.map(_.uci.uci))
          .add("san", moveOption.map(_.san))
          .add("check", check)
          .add("eval", eval.filterNot(_.isEmpty))
          .add("comments", if (comments.nonEmpty) Some(comments) else None)
          .add("gamebook", gamebook)
          .add("glyphs", glyphs.nonEmpty)
          .add("shapes", if (shapes.value.nonEmpty) Some(shapes.value) else None)
          .add("opening", opening)
          .add("dests", dests)
          .add(
            "drops",
            drops.map { drops =>
              JsString(drops.map(_.key).mkString)
            }
          )
          .add("clock", clock)
          .add("crazy", crazyData)
          .add("comp", comp)
          .add(
            "children",
            if (alwaysChildren || children.nonEmpty) Some {
              nodeListJsonWriter(true) writes children
            }
            else None
          )
          .add("forceVariation", forceVariation)
      catch
        case e: StackOverflowError =>
          e.printStackTrace()
          sys error s"### StackOverflowError ### in tree.makeNodeJsonWriter($alwaysChildren)"
    }

  def destString(dests: Map[Pos, List[Pos]]): String =
    val sb    = new java.lang.StringBuilder(80)
    var first = true
    dests foreach { (orig, dests) =>
      if (first) first = false
      else sb append " "
      sb append orig.toChar
      dests foreach { sb append _.toChar }
    }
    sb.toString

  given Writes[Map[Pos, List[Pos]]] = Writes { dests =>
    JsString(destString(dests))
  }

  val partitionTreeJsonWriter: Writes[Node] = Writes { node =>
    JsArray {
      node.mainlineNodeList.map(minimalNodeJsonWriter.writes)
    }
  }
