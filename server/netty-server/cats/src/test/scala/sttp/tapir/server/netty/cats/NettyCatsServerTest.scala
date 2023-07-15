package sttp.tapir.server.netty.cats

import cats.effect.{IO, Resource}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import sttp.capabilities.fs2.Fs2Streams
import sttp.monad.MonadError
import sttp.tapir.integ.cats.effect.CatsMonadError
import sttp.tapir.server.netty.internal.FutureUtil
import sttp.tapir.server.tests._
import sttp.tapir.tests.{Test, TestSuite}

class NettyCatsServerTest extends TestSuite with EitherValues with StrictLogging with Matchers {
  override def tests: Resource[IO, List[Test]] =
    backendResource.flatMap { backend =>
      Resource
        .make {
          implicit val m: MonadError[IO] = new CatsMonadError[IO]()
          val eventLoopGroup = new NioEventLoopGroup()

          val interpreter = new NettyCatsTestServerInterpreter(eventLoopGroup, dispatcher)
          val createServerTest = new DefaultCreateServerTest(backend, interpreter)

          val tests = new AllServerTests(createServerTest, interpreter, backend, multipart = false).tests() ++ new ServerStreamingTests(createServerTest, Fs2Streams[IO]).tests()

          IO.pure((tests, eventLoopGroup))
        } { case (_, eventLoopGroup) =>
          IO.fromFuture(IO.delay(FutureUtil.nettyFutureToScala(eventLoopGroup.shutdownGracefully()))).void
        }
        .map { case (tests, _) => tests }
    }
}
