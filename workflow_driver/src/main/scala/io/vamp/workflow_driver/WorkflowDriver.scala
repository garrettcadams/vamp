package io.vamp.workflow_driver

import akka.actor.ActorSystem
import io.vamp.common.akka.CommonSupportForActors
import io.vamp.common.akka.IoC._
import io.vamp.common.config.Config
import io.vamp.common.notification.Notification
import io.vamp.common.vitals.InfoRequest
import io.vamp.container_driver.ContainerDriverActor
import io.vamp.model.artifact._
import io.vamp.model.reader.{ MegaByte, Quantity }
import io.vamp.model.resolver.TraitResolver
import io.vamp.persistence.db.{ ArtifactSupport, PersistenceActor }
import io.vamp.pulse.notification.PulseFailureNotifier
import io.vamp.workflow_driver.WorkflowDriverActor.{ GetScheduled, Schedule, Unschedule }
import io.vamp.workflow_driver.notification.WorkflowDriverNotificationProvider

import scala.concurrent.Future

case class WorkflowInstance(name: String)

object WorkflowDeployable {

  val javascript = "application/javascript"

  private val deployables = s"${WorkflowDriver.config}.workflow.deployables"

  private val javascriptDeployable = () ⇒ Deployable(Config.string(s"$deployables.application/javascript.type")(), Config.string(s"$deployables.application/javascript.definition")())

  def matches(some: Deployable): Boolean = some.`type` == javascript

  def provide(some: Deployable): Deployable = javascriptDeployable()
}

object WorkflowDriver {

  val root = "workflows"

  val config = "vamp.workflow-driver"

  def path(workflow: Workflow) = root :: workflow.name :: Nil
}

trait WorkflowDriver extends ArtifactSupport with PulseFailureNotifier with CommonSupportForActors with WorkflowDriverNotificationProvider with TraitResolver {

  import WorkflowDriver._

  implicit def actorSystem: ActorSystem

  implicit val timeout = ContainerDriverActor.timeout()

  val globalEnvironmentVariables: List[EnvironmentVariable] = Config.stringList(s"$config.workflow.environment-variables")().map { env ⇒
    val index = env.indexOf('=')
    EnvironmentVariable(env.substring(0, index), None, Option(env.substring(index + 1)), None)
  }

  val defaultScale = DefaultScale(
    "",
    Quantity.of(Config.double(s"$config.workflow.scale.cpu")()),
    MegaByte.of(Config.string(s"$config.workflow.scale.memory")()),
    Config.int(s"$config.workflow.scale.instances")()
  )

  val defaultArguments: List[Argument] = Config.stringList(s"$config.workflow.arguments")().map(Argument(_))

  val defaultNetwork = Config.string(s"$config.workflow.network")()

  val defaultCommand = Config.string(s"$config.workflow.command")()

  def receive = {
    case InfoRequest              ⇒ reply(info)
    case GetScheduled(workflows)  ⇒ request(workflows)
    case Schedule(workflow, data) ⇒ reply(schedule(data)(workflow))
    case Unschedule(workflow)     ⇒ reply(unschedule()(workflow))
  }

  protected def info: Future[Map[_, _]]

  protected def request(workflows: List[Workflow]): Unit

  protected def schedule(data: Any): PartialFunction[Workflow, Future[Any]]

  protected def unschedule(): PartialFunction[Workflow, Future[Any]]

  protected def enrich: Workflow ⇒ Workflow = { workflow ⇒

    val breed = workflow.breed.asInstanceOf[DefaultBreed]

    val environmentVariables = {
      resolveGlobal(workflow) ++ breed.environmentVariables ++ workflow.environmentVariables
    }.map(env ⇒ env.name → env.copy(interpolated = env.value)).toMap.values.toList

    actorFor[PersistenceActor] ! PersistenceActor.UpdateWorkflowEnvironmentVariables(workflow, environmentVariables)

    val deployable = breed.deployable match {
      case d if WorkflowDeployable.matches(d) ⇒ WorkflowDeployable.provide(d)
      case d                                  ⇒ d
    }

    val scale = workflow.scale.getOrElse(defaultScale).asInstanceOf[DefaultScale]
    actorFor[PersistenceActor] ! PersistenceActor.UpdateWorkflowScale(workflow, scale)

    val network = workflow.network.getOrElse(defaultNetwork)
    actorFor[PersistenceActor] ! PersistenceActor.UpdateWorkflowNetwork(workflow, network)

    val arguments = (defaultArguments ++ breed.arguments ++ workflow.arguments).map(arg ⇒ arg.key → arg).toMap.values.toList
    actorFor[PersistenceActor] ! PersistenceActor.UpdateWorkflowArguments(workflow, arguments)

    workflow.copy(
      breed = breed.copy(deployable = deployable, environmentVariables = environmentVariables),
      scale = Option(scale),
      arguments = arguments,
      network = Option(network),
      environmentVariables = environmentVariables
    )
  }

  private def resolveGlobal(workflow: Workflow): List[EnvironmentVariable] = {
    def interpolated: ValueReference ⇒ String = {
      case ref: LocalReference if ref.name == "workflow" ⇒ workflow.name
      case _ ⇒ ""
    }

    globalEnvironmentVariables.map { env ⇒
      val value = resolve(env.value.getOrElse(""), interpolated)
      env.copy(value = Option(value), interpolated = Option(value))
    }
  }

  override def failure(failure: Any, `class`: Class[_ <: Notification] = errorNotificationClass): Exception = super[PulseFailureNotifier].failure(failure, `class`)
}
