package board.controller

import board.entity._

import org.json4s._
import org.json4s.native.Serialization.{read, write}

import simplehttpserver.impl._
import simplehttpserver.util.{Security, EasyEmit}
import simplehttpserver.util.implicits.Implicit._
import simplehttpserver.util.Security._
import simplehttpserver.util.Common._

object BoardController extends EasyEmit {
  type Action = HttpRequest => HttpResponse
  implicit private val formats = DefaultFormats


  private val path2PostData = "./private/post.json"
  private val path2UserData = "./private/user.json"

  private val builder = new HtmlBuilder()
  private val base = getStringFromResources("base.html")
  private val buildWithBase = builder.buildHtml(base getOrElse "") _
  private val signUpBase = getStringFromResources("signUpBase.html")
  //  private val buildWithSignUpBase = builder.buildHtml(signUpBase getOrElse "") _
  private val loginBase = getStringFromResources("loginBase.html")
  private val buildWithLoginBase = builder.buildHtml(loginBase getOrElse "") _
  private val postBase = getStringFromResources("postBase.html")
  private val buildWithPostBase = builder.buildHtml(postBase getOrElse "") _
  private val boardBase = getStringFromResources("boardBase.html")
  private val buildWithBoardBase = builder.buildHtml(boardBase getOrElse "") _

  //sign up page
  def signUpPage: Action = req => {
    val contentType = "Content-Type" -> html.contentType
    val title = "Sing up"

    HttpResponse(req)(
      status = Ok,
      header = Map(contentType),
      body = buildWithBase(Seq(
        "title" -> title,
        "head" -> "",
        "body" -> (signUpBase getOrElse "")
      )))
  }

  //sign up process
  def signUp: Action = req => {
    (for {
      id <- req.body.get("id")
      pwd <- req.body.get("password")
      name <- req.body.get("name")
    } yield {
      //id, pwd に使えない文字が入っていた場合は再度signUpPageへ
      if (!validate4Id(id) || !validate4Pwd(pwd))
        signUpPage(req) //TODO:msg表示
      else if (isExistingUser(id))
        signUpPage(req) //TODO:msg表示
      else {
        val salt = Security.hashBySHA384(id)
        val hashedPwd = Security.hashBySHA384(pwd + salt)
        val newUsers = getUsers :+ User(id, hashedPwd, name)

        writeWithResult(path2UserData)(pw => {
          pw.print(write(newUsers))
          /*val newSession =*/ req.refreshSession

//          val contentType = "Content-Type" -> html.contentType
//          val title = "Login"
//          val head = """<script src="//ajax.googleapis.com/ajax/libs/jquery/1.11.1/jquery.min.js"></script>
//                       |<script src="./js/script.js" type="text/javascript"></script>
//                       |<link href="./css/style.css" rel="stylesheet" type="text/css">""".stripMargin
//
//          HttpResponse(req)(
//            status = Ok,
//            header = Map(contentType,
//              "Set-Cookie" -> s"SESSIONID=${newSession.sessionId}"),
//            body = "")
          boardG(req)
        })(ex => {
          emitError(req)(InternalServerError)
        })
      }
    }) getOrElse emitError(null)(InternalServerError)
  }

  //TODO: ajax用に公開
  private def validate4Id(id: String): Boolean = {
    id forall { c =>
      c != '<' || c != '>' || c != '"' || c != '\'' || c != '\\'
    }
  }

  private def validate4Pwd(pwd: String): Boolean = {
    pwd forall { c =>
      c != '<' || c != '>' || c != '"' || c != '\'' || c != '\\'
    }
  }

  //TODO: ajax用に公開
  private def isExistingUser(id: String): Boolean = {
    getUsers exists (_.id == id)
  }

  private def getUsers: List[User] = {
    getStringFromFile(path2UserData) match {
      case Some(json) => read[List[User]](json)
      case None => throw new Exception("route file not found")
    }
  }

  //login page
  def loginPage: Action = req => {
    val contentType = "Content-Type" -> html.contentType
    val title = "Login"

    null
  }

  //login process
  def login: Action = req => {
    null
  }

//TODO:sessionをチェックし, boardPageを利用するloggedinとして切り分ける
//TODO:boardPageに変更
  def boardG: Action = req => {
    val contentType = "Content-Type" -> html.contentType
    val title = "Board"
    val head = """<script src="//ajax.googleapis.com/ajax/libs/jquery/1.11.1/jquery.min.js"></script>
                 |<script src="/js/script.js" type="text/javascript"></script>
                 |<link href="/css/style.css" rel="stylesheet" type="text/css">""".stripMargin

    val posts = getStringFromFile(path2PostData) match {
      case Some(json) => read[List[Post]](json)
      case None => throw new Exception("route file not found")
    }

    val formed =
      posts
        .withFilter(_.enabled)
        .map(post => {
        val imgs = post.imgs
          .map(i => s"""<img src="$i" class="img" />""")
          .mkString
        buildWithPostBase(Seq(
          "name" -> post.name,
          "date" -> post.date.map(_.formatted("%tF %<tT")).mkString,
          "content" -> post.content,
          "imgs" -> imgs
        ))
      })

    val sessionId = req.session.map(_.sessionId).getOrElse("")
    val user = req.session flatMap {
      s => getUsers find (_.id == s.id)
    }

    val body = buildWithBoardBase(Seq(
      "name" -> user.map(_.name).getOrElse(""),
      "posts" -> formed.mkString
    ))

    HttpResponse(req)(
      Ok,
      header = Map(contentType,
        "Set-Cookie" -> s"SESSIONID=$sessionId"),
      body = buildWithBase(Seq(
        "title" -> title,
        "head" -> head,
        "body" -> body))
    )
  }

  //data.json取得, postを解析して追加, そこからresponse作成しつつdata.json更新
  def boardP: Action = req => {
    val contentType = "Content-Type" -> html.contentType
    val title = "Board"
    val head = """<script src="//ajax.googleapis.com/ajax/libs/jquery/1.11.1/jquery.min.js"></script>
                 |<script src="./js/script.js" type="text/javascript"></script>
                 |<link href="./css/style.css" rel="stylesheet" type="text/css">""".stripMargin

    val posts = getStringFromFile(path2PostData) match {
      case Some(json) => read[List[Post]](json)
      case None => throw new Exception("route file not found")
    }

    val newPosts = {
      val name = req.body.getOrElse("name", "")
      val date = Some(new java.util.Date())
      val content = req.body.getOrElse("content", "")
      val imgs = req.body.get("imgs") match {
        case Some(x) => List() //TODO: x:String(=json array) を List[String] に変換
        case None => List()
      }
      posts :+ Post(
        true,
        escape(name) getOrElse "",
        date,
        escape(content) getOrElse "",
        imgs map {
          escape(_) getOrElse ""
        })
    }
    val added = write(newPosts)

    writeWithResult(path2PostData)(pw => {
      pw.print(added)

      val formed =
        newPosts
          .withFilter(_.enabled)
          .map(post => {
          val imgs = post.imgs
            .map(i => s"""<img src="$i" class="img" />""")
            .mkString
          buildWithPostBase(Seq(
            "name" -> post.name,
            "date" -> post.date.map(_.formatted("%tF %<tT")).mkString,
            "content" -> post.content,
            "imgs" -> imgs
          ))
        })

      val body = buildWithBoardBase(Seq(
        "posts" -> formed.mkString
      ))

      HttpResponse(req)(
        Ok,
        header = Map(contentType),
        body = buildWithBase(Seq(
          "title" -> title,
          "head" -> head,
          "body" -> body))
      )
    })(ex => {
      println(ex)
      emitError(req)(InternalServerError)
    })
  }
}
