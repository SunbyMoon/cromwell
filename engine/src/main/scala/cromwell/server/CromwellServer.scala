package cromwell.server

import akka.actor.{ActorContext, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cromwell.core.Dispatcher.EngineDispatcher
import cromwell.webservice.{CromwellApiService, SwaggerService}

import scala.concurrent.Future
import scala.util.{Failure, Success}

// Note that as per the language specification, this is instantiated lazily and only used when necessary (i.e. server mode)
object CromwellServer {
  private var cromwellServerActor: Option[ActorRef] = None

  def run(cromwellSystem: CromwellSystem): Future[Any] = {
    implicit val actorSystem = cromwellSystem.actorSystem
    implicit val materializer = cromwellSystem.materializer
    cromwellServerActor = Option(actorSystem.actorOf(CromwellServerActor.props(cromwellSystem), "cromwell-service"))
    actorSystem.whenTerminated
  }

  def abort(): Future[Boolean] = cromwellServerActor match {
    case Some(actorRef) => CromwellRootActor.abort(actorRef)
    case None => Future.failed(new IllegalStateException("Attempted to abort Cromwell server but could not find a running server actor."))
  }

  def gracefullyStop(): Future[Boolean] = cromwellServerActor match {
    case Some(actorRef) => CromwellRootActor.gracefullyStop(actorRef)
    case None => Future.failed(new IllegalStateException("Attempted to gracefully stop Cromwell server but could not find a running server actor."))
  }
}

class CromwellServerActor(cromwellSystem: CromwellSystem)(override implicit val materializer: ActorMaterializer)
  extends CromwellRootActor
    with CromwellApiService
    with SwaggerService {
  implicit val actorSystem = context.system
  override implicit val ec = context.dispatcher
  override def actorRefFactory: ActorContext = context

  override val serverMode = true
  override val abortJobsOnTerminate = false
  override val gracefulShutdown = true

  val webserviceConf = cromwellSystem.conf.getConfig("webservice")
  val interface = webserviceConf.getString("interface")
  val port = webserviceConf.getInt("port")
  //  var serverBinding: Option[Future[Http.ServerBinding]] = None

  override def preShutDown(): Future[Unit] = serverBinding flatMap { _.unbind() }
  

  /**
    * /api routes have special meaning to devops' proxy servers. NOTE: the oauth mentioned on the /api endpoints in
    * cromwell.yaml is broken unless the swagger index.html is patched. Copy/paste the code from rawls or cromiam if
    * actual cromwell+swagger+oauth+/api support is needed.
    */
  val apiRoutes: Route = pathPrefix("api")(concat(workflowRoutes))
  val nonApiRoutes: Route = concat(engineRoutes, swaggerUiResourceRoute)
  val allRoutes: Route = concat(apiRoutes, nonApiRoutes)

  val serverBinding = Http().bindAndHandle(allRoutes, interface, port)
  
  serverBinding onComplete {
    case Success(_) => actorSystem.log.info("Cromwell service started...")
    case Failure(e) =>
      /*
        TODO:
        If/when CromwellServer behaves like a better async citizen, we may be less paranoid about our async log messages
        not appearing due to the actor system shutdown. For now, synchronously print to the stderr so that the user has
        some idea of why the server failed to start up.
      */
      Console.err.println(s"Binding failed interface $interface port $port")
      e.printStackTrace(Console.err)
      cromwellSystem.shutdownActorSystem()
  }

  /*
    During testing it looked like not explicitly invoking the WMA in order to evaluate all of the lazy actors in
    CromwellRootActor would lead to weird timing issues the first time it was invoked organically
   */
  workflowManagerActor
}

object CromwellServerActor {
  def props(cromwellSystem: CromwellSystem)(implicit materializer: ActorMaterializer): Props = {
    Props(new CromwellServerActor(cromwellSystem)).withDispatcher(EngineDispatcher)
  }
}
