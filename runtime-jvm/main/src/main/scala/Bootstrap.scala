package org.unisonweb

import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.unisonweb.ABT.Name
import org.unisonweb.BuiltinTypes._
import org.unisonweb.Term.Term
import org.unisonweb.compilation._
import org.unisonweb.util._

import scala.util.Try

object Bootstrap {
  import Bootstrap0._

  def main(args: Array[String]): Unit = {

    val fileName = args match {
      case Array(s) => s
      case _ =>
        println("usage: Bootstrap <file.ub>")
        sys.exit(1)
    }
    println {
      PrettyPrint
        .prettyTerm(normalizedFromBinaryFile(fileName))
        .render(80)
    }
  }
}

object BootstrapStream {
  import Bootstrap0._
  def usage(): Nothing = {
    println {
      """usage: BootstrapStream <port>
        |  where <port> is the local tcp port the codebase editor is listening on.
      """.stripMargin
    }
    sys.exit(1)
  }

  def main(args: Array[String]): Unit = {
    val localPort: Int = args match {
      case Array(s) => Try { s.toInt }.getOrElse(usage())
      case _ => usage()
    }

    val channel =
      Try {
        SocketChannel.open(
          new InetSocketAddress(InetAddress.getLoopbackAddress, localPort))
      }.getOrElse {
        println(s"Couldn't connect to codebase editor on port $localPort.")
        sys.exit(1)
      }
    while(true) {
      val wrangle = false
      val t = normalizedFromBinarySource(Source.fromSocketChannel(channel))
      if (wrangle) {
        // serialize term back to the channel
        val serialized = Codecs.encodeTerm(t)
        def go(s: Sequence[Array[Byte]]): Unit = s.headOption match {
          case Some(array) =>
            channel.write(ByteBuffer.wrap(array))
            go(s.drop(1))
          case None => ()
        }
      }
      else () // println(PrettyPrint.prettyTerm(t).render(80))
    }

  }
}

object Bootstrap0 {

  def watchHandler(label: String, v: Value): Unit = {
    println(label)
    // val lead = "      | > "
    val lead = "      | > "
    val arr = "          ⧩"
    val tm = PrettyPrint.prettyTerm(Term.fullyDecompile(v.decompile)).render(80)
    val tm2 = tm.flatMap {
      case '\n' => '\n' + (" " * lead.size)
      case ch    => ch.toString
    }
    println(arr)
    println((" " * lead.size) + tm2 + "\n")
  }

  def normalizedFromBinaryFile(fileName: String, wh: (String, Value) => Unit = watchHandler): Term =
    normalizedFromBinarySource(Source.fromFile(fileName), wh)

  def normalizedFromBinarySource(src: Source, wh: (String, Value) => Unit = watchHandler): Term = {
    fromBinarySource(src, normalize0(_, wh)(_))
  }

  def fromBinarySource[A](src: Source, f: (Environment, Term) => A): A = {
    import org.unisonweb.Codecs.{decodeConstructorArities, termDecoder}

    val data = decodeConstructorArities(src)
    val effects = decodeConstructorArities(src)

    val term = termDecoder(src)

    val datas = data.flatMap { case (id, arities) =>
      arities.zipWithIndex.map {
        case (arity, cid) =>
          dataConstructor(id, ConstructorId(cid),
                          Range(0,arity).map(x => Name("x" + x)):_*)
      }
    }.toMap

    val effectss = effects.flatMap { case (id, arities) =>
      arities.zipWithIndex.map {
        case (arity, cid) =>
          effectRequest(id, ConstructorId(cid),
                        Range(0,arity).map(x => Name("x" + x)):_*)
      }
    }.toMap

    val env =
      Environment.standard.copy(
        dataConstructors = Environment.standard.dataConstructors ++ datas,
        effects = Environment.standard.effects ++ effectss
      )

    f(env, term)
  }

  val PROJECT_ROOT_ENV_NAME = "UNISON_PROJECT_ROOT"
  val UNISON_PROJECT_ROOT =
    new File(Option(System.getProperty(PROJECT_ROOT_ENV_NAME))
               .orElse(Option(System.getenv(PROJECT_ROOT_ENV_NAME)))
               .getOrElse(".."))

  private implicit class Ops[A](val a: A) extends AnyVal {
    def unsafeTap(f: A => Unit): A = { f(a); a }
  }
  def normalizedFromTextFile(u: Path,
                             stackDir: File = UNISON_PROJECT_ROOT
                            ): Either[String,Term] = {
    if (! new File(UNISON_PROJECT_ROOT, "stack.yaml").isFile) Left {
      import java.nio.file.{FileSystems, Path}

      val path: Path = FileSystems.getDefault.getPath(".").toAbsolutePath
      s"""Expected to find `stack.yaml` in `$UNISON_PROJECT_ROOT` to `stack exec bootstrap`,
         |but didn't. Specify the correct directory by setting a Java system property or
         |system environment variable called `$PROJECT_ROOT_ENV_NAME`.
         |Current directory is: $path"""
        .stripMargin
    }
    else {
      import sys.process._
      val ub = Files.createTempFile(u.getFileName.toString, ".ub")
      val stderrBuffer = new StringBuffer
      val log = new ProcessLogger {
        def buffer[T](f: => T): T = f
        def out(s: => String): Unit = ()
        def err(s: => String): Unit = {
          stderrBuffer.append(s)
          stderrBuffer.append("\n")
          ()
        }
      }
      val haskellResult =
        Process(Seq("stack", "build"), stackDir) #&&
          Process(Seq("stack", "exec", "bootstrap", u.toString, ub.toString)) ! log

      { if (haskellResult > 0) Left(stderrBuffer.toString)
        else Right(normalizedFromBinaryFile(ub.toString)) }
        .unsafeTap(_ => Files.delete(ub))
    }
  }

  def normalizedFromText(s: String,
                         stackDir: File = UNISON_PROJECT_ROOT
                        ): Either[String, Term] = {
    val u = Files.createTempFile("", ".u")
    Files.write(u, s.getBytes(StandardCharsets.UTF_8))
    normalizedFromTextFile(u, stackDir)
        .unsafeTap(_ => Files.delete(u))
  }
}