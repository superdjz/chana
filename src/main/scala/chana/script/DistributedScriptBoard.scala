package chana.script

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.pattern.ask
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.script.Compilable
import javax.script.CompiledScript
import javax.script.ScriptEngineManager
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

/**
 * Extension that starts a [[DistributedScriptBoard]] actor
 * with settings defined in config section `chana.script-board`.
 */
object DistributedScriptBoard extends ExtensionId[DistributedScriptBoardExtension] with ExtensionIdProvider {
  // -- implementation of akka extention 
  override def get(system: ActorSystem) = super.get(system)
  override def lookup = DistributedScriptBoard
  override def createExtension(system: ExtendedActorSystem) = new DistributedScriptBoardExtension(system)
  // -- end of implementation of akka extention 

  /**
   * Scala API: Factory method for `DistributedScriptBoard` [[akka.actor.Props]].
   */
  def props(): Props = Props(classOf[DistributedScriptBoard])

  /**
   * There is a sbt issue related to classloader, anyway, use new ScriptEngineManager(null),
   * by adding 'null' classloader solves this issue:
   * https://github.com/playframework/playframework/issues/2532
   */
  lazy val engineManager = new ScriptEngineManager(null)
  lazy val engine = engineManager.getEngineByName("nashorn").asInstanceOf[Compilable]

  private val keyToScript = new mutable.HashMap[String, CompiledScript]()
  private val entityToScripts = new mutable.HashMap[String, Map[String, CompiledScript]].withDefaultValue(Map.empty)
  private val entityXpathToScripts = new mutable.HashMap[String, Map[String, CompiledScript]].withDefaultValue(Map.empty)
  private val scriptsLock = new ReentrantReadWriteLock()
  private def keyOf(entity: String, xpath: String, scriptId: String) = entity + "/" + xpath + "/" + scriptId

  private def putScript(key: String, compiledScript: CompiledScript): Unit = key.split('/') match {
    case Array(entity, field, id) => putScript(entity, field, id, compiledScript)
    case _                        =>
  }
  private def putScript(entity: String, xpath: String, scriptId: String, compiledScript: CompiledScript): Unit = {
    val entityXpath = entity + "/" + xpath
    val key = entityXpath + "/" + scriptId
    try {
      scriptsLock.writeLock.lock
      keyToScript(key) = compiledScript
      entityToScripts(entity) = entityToScripts(entity) + (scriptId -> compiledScript)
      entityXpathToScripts(entityXpath) = entityXpathToScripts(entityXpath) + (scriptId -> compiledScript)
    } finally {
      scriptsLock.writeLock.unlock
    }
  }

  private def removeScript(key: String): Unit = key.split('/') match {
    case Array(entity, field, id) => removeScript(entity, field, id)
    case _                        =>
  }
  private def removeScript(entity: String, field: String, id: String): Unit = {
    val entityField = entity + "/" + field
    val key = entityField + "/" + id
    try {
      scriptsLock.writeLock.lock
      keyToScript -= key
      entityToScripts(entity) = entityToScripts(entity) - id
      entityXpathToScripts(entityField) = entityXpathToScripts(entityField) - id
    } finally {
      scriptsLock.writeLock.unlock
    }
  }

  def scriptsOf(entity: String, xpath: String): Map[String, CompiledScript] = {
    try {
      scriptsLock.readLock.lock
      entityXpathToScripts(entity + xpath) // TODO
      //entityXpathToScripts(entity + "/" + xpath)
    } finally {
      scriptsLock.readLock.unlock
    }
  }

  def scriptsOf(entity: String): Map[String, CompiledScript] = {
    try {
      scriptsLock.readLock.lock
      entityToScripts(entity)
    } finally {
      scriptsLock.readLock.unlock
    }
  }

  val DataKey = LWWMapKey[String]("chana-scripts")
}

class DistributedScriptBoard extends Actor with ActorLogging {
  import akka.cluster.ddata.Replicator._

  implicit val cluster = Cluster(context.system)
  import context.dispatcher

  val replicator = DistributedData(context.system).replicator
  replicator ! Subscribe(DistributedScriptBoard.DataKey, self)

  def receive = {
    case chana.PutScript(entity, field, id, script) =>
      val commander = sender()
      compileScript(script) match {
        case Success(compiledScript) =>
          val key = DistributedScriptBoard.keyOf(entity, field, id)

          replicator.ask(Update(DistributedScriptBoard.DataKey, LWWMap(), WriteAll(60.seconds))(_ + (key -> script)))(60.seconds).onComplete {
            case Success(_: UpdateSuccess[_]) =>
              DistributedScriptBoard.putScript(key, compiledScript)
              log.info("put script (Update) [{}]:\n{} ", key, script)
              commander ! Success(key)
            case Success(_: UpdateTimeout[_]) => commander ! Failure(chana.UpdateTimeoutException)
            case Success(x: ModifyFailure[_]) => commander ! Failure(x.cause)
            case Success(x)                   => log.warning("Got {}", x)
            case ex: Failure[_]               => commander ! ex
          }

        case Failure(ex) =>
          log.error(ex, ex.getMessage)
      }
    case chana.RemoveScript(entity, field, id) =>
      val commander = sender()
      val key = DistributedScriptBoard.keyOf(entity, field, id)

      replicator.ask(Update(DistributedScriptBoard.DataKey, LWWMap(), WriteAll(60.seconds))(_ - key))(60.seconds).onComplete {
        case Success(_: UpdateSuccess[_]) =>
          log.info("remove script (Update): {}", key)
          DistributedScriptBoard.removeScript(key)
          commander ! Success(key)
        case Success(_: UpdateTimeout[_]) => commander ! Failure(chana.UpdateTimeoutException)
        case Success(x: ModifyFailure[_]) => commander ! Failure(x.cause)
        case Success(x)                   => log.warning("Got {}", x)
        case ex: Failure[_]               => commander ! ex
      }

    case c @ Changed(DistributedScriptBoard.DataKey) =>
      // check if there were newly added
      val entries = c.get(DistributedScriptBoard.DataKey).entries
      entries.foreach {
        case (key, script) =>
          DistributedScriptBoard.keyToScript.get(key) match {
            case None =>
              compileScript(script) match {
                case Success(compiledScript) =>
                  DistributedScriptBoard.putScript(key, compiledScript)
                  log.info("put script (Changed) [{}]:\n{} ", key, script)
                case Failure(ex) =>
                  log.error(ex, ex.getMessage)
              }
            case Some(script) => // TODO, existed, but changed?
          }
      }

      // check if there were removed
      val toRemove = DistributedScriptBoard.keyToScript.filter(x => !entries.contains(x._1)).keys
      if (toRemove.nonEmpty) {
        log.info("remove script (Changed): {}", toRemove)
        toRemove foreach DistributedScriptBoard.removeScript
      }
  }

  private def compileScript(script: String) =
    try {
      val compiledScript = DistributedScriptBoard.engine.compile(script)
      Success(compiledScript)
    } catch {
      case ex: Throwable => Failure(ex)
    }

}

class DistributedScriptBoardExtension(system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config.getConfig("chana.script-board")
  private val role: Option[String] = config.getString("role") match {
    case "" => None
    case r  => Some(r)
  }

  /**
   * Returns true if this member is not tagged with the role configured for the
   * mediator.
   */
  def isTerminated: Boolean = Cluster(system).isTerminated || !role.forall(Cluster(system).selfRoles.contains)

  /**
   * The [[DistributedScriptBoard]]
   */
  val board: ActorRef = {
    if (isTerminated)
      system.deadLetters
    else {
      val name = config.getString("name")
      system.actorOf(
        DistributedScriptBoard.props(),
        name)
    }
  }
}

