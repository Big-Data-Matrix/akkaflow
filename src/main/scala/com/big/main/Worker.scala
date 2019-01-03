package com.big.main

import akka.actor.Actor
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.Member
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ ask, pipe }
import akka.actor.RootActorPath
import akka.actor.ActorPath
import akka.actor.ActorRef
import com.kent.workflow.node.ActionNodeInstance
import com.kent.workflow.ActionActor
import com.kent.db.LogRecorder
import com.typesafe.config.Config
import com.kent.pub.Event._
import scala.concurrent.Future
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.Success
import com.kent.pub.ActorTool
import com.kent.pub.ClusterRole
import akka.actor.OneForOneStrategy
import java.sql.SQLException

/**
 * worker工作节点
 */
class Worker extends ClusterRole {
	import com.kent.main.Worker._
	//运行中的action节点  【actioninstance_name,ar】
  var runningActionActors = Map[String,ActorRef]()
  init()

  override def supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 30 second){
    case _:SQLException => akka.actor.SupervisorStrategy.Restart
    case _:Exception => akka.actor.SupervisorStrategy.Restart
  }

  def indivivalReceive: Actor.Receive = {
    case CreateAction(ani) => sender ! createActionActor(ani)
    case RemoveAction(name) => runningActionActors = runningActionActors - name
    case KillAllActionActor() => killAllActionActor() pipeTo sender
  }
  /**
   * 创建action actor
   */
  def createActionActor(actionNodeInstance: ActionNodeInstance):ActorRef = {
		val actionActorRef = context.actorOf(Props(ActionActor(actionNodeInstance)), actionNodeInstance.hashCode()+"")
		runningActionActors = runningActionActors + (actionNodeInstance.name -> actionActorRef)
		actionActorRef
  }
  /**
   * 杀死所有的action actor
   */
  def killAllActionActor():Future[Boolean] = {
    val resultsF = runningActionActors.map { case (x,y) => (y ? Kill()).mapTo[ActionExecuteResult] }.toList
    val rsF = Future.sequence(resultsF).map { x =>
      this.runningActionActors = Map.empty[String, ActorRef]
      true
    }
    rsF
  }
  /**
   * 初始化
   */
  def init(){
    val config = context.system.settings.config
    //日志记录器配置
    //mysql持久化参数配置
    val mysqlConfig = (config.getString("workflow.mysql.user"),
                      config.getString("workflow.mysql.password"),
                      config.getString("workflow.mysql.jdbc-url"),
                      config.getBoolean("workflow.mysql.is-enabled")
                    )
    //创建日志记录器
    Worker.logRecorder = context.actorOf(Props(LogRecorder(mysqlConfig._3,mysqlConfig._1,mysqlConfig._2,mysqlConfig._4)),"log-recorder")
    LogRecorder.actor = Worker.logRecorder
  }
}

object Worker extends App {
  import scala.collection.JavaConverters._
  val defaultConf = ConfigFactory.load()
  val workersConf = defaultConf.getStringList("workflow.nodes.workers").asScala.map { x => val y = x.split(":");(y(0),y(1)) }.toList
  var logRecorder: ActorRef = _
  var config:Config = _
  workersConf.foreach{ info =>
    val hostConf = "akka.remote.netty.tcp.hostname=" + info._1
    val portConf = "akka.remote.netty.tcp.port=" + info._2
    val config = ConfigFactory.parseString(hostConf)
        .withFallback(ConfigFactory.parseString(portConf))
        .withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [${RoleType.WORKER}]"))
        .withFallback(defaultConf)
    Worker.config = config
    val system = ActorSystem("akkaflow", config)
    val worker = system.actorOf(Worker.props, name = RoleType.WORKER)
  }
  def props = Props[Worker]
}
