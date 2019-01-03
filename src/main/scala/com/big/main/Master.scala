package com.big.main

import akka.actor.Actor
import akka.cluster.ClusterEvent._
import com.kent.pub.ClusterRole.Registration
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import com.kent.workflow.WorkFlowManager
import com.kent.db.PersistManager
import akka.pattern.{ ask, pipe }
import com.kent.workflow.node.ActionNodeInstance
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import com.kent.pub.Event._
import scala.util.Random
import com.typesafe.config.Config
import com.kent.mail.EmailSender
import com.kent.db.LogRecorder
import akka.cluster.Member
import akka.actor.ActorPath
import akka.actor.RootActorPath
import com.kent.db.XmlLoader
import scala.util.Success
import scala.concurrent.Future
import com.kent.ddata.HaDataStorager
import com.kent.ddata.HaDataStorager.AddWorkflow
import com.kent.workflow.WorkflowInfo
import com.kent.ddata.HaDataStorager._
import com.kent.workflow.WorkflowInstance
import com.kent.coordinate.CronComponent
import scala.concurrent.Await
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionKey
import akka.cluster.Cluster
import com.kent.pub.ActorTool.ActorInfo
import com.kent.pub.ActorTool.ActorType._
import com.kent.pub.ActorTool
import com.kent.pub.ClusterRole
import akka.actor.PoisonPill
import jnr.ffi.annotations.Synchronized
import com.kent.pub.CustomException._
import java.sql.SQLException



class Master(var isActiveMember:Boolean) extends ClusterRole {
  import com.kent.pub.ClusterRole.RStatus._
  var workflowManager: ActorRef = _
  var xmlLoader: ActorRef = _
  var httpServerRef:ActorRef = _
  var workers = List[ActorRef]()
  //其他
  var otherMaster:ActorRef = _
  //角色状态
  var status:RStatus = R_PREPARE

  /**
   * 监控策略
   */
  import akka.actor.OneForOneStrategy
  override def supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 30 second){
    case _:SQLException => akka.actor.SupervisorStrategy.Restart
    case _:Exception => akka.actor.SupervisorStrategy.Restart
  }
  //创建集群高可用数据分布式数据寄存器
  Master.haDataStorager = context.actorOf(Props[HaDataStorager],"ha-data")
  /**
   * 当集群节点可用时
   */
  Cluster(context.system).registerOnMemberUp({
    if(isActiveMember) active() else standby()
  })
  def indivivalReceive: Actor.Receive = {
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
      registerRoleMember(member)
    case StartIfActive(isAM) => (if(isAM) active() else standby()) pipeTo sender
    case Terminated(ar) =>
      //若是worker，则删除
      workers = workers.filterNot(_ == ar)
      //若终止的是活动主节点，则需要激活当前备份节点
      if(ar == otherMaster && !this.isActiveMember){
        log.info(s"${RoleType.MASTER_STANDBY}节点准备切换到${RoleType.MASTER_ACTIVE}节点")
        val hostPortKey = ar.path.address.host.get + ":" + ar.path.address.port.get
        Master.haDataStorager ! removeRole(hostPortKey)
        otherMaster = null
        this.active()
      }
    case KillAllActionActor() =>
      val kwaFl = this.workers.map { worker => (worker ? KillAllActionActor()).mapTo[Boolean] }.toList
		  val kwalF = Future.sequence(kwaFl)
		  kwalF pipeTo sender
    case AskWorker(host: String) => sender ! allocateWorker(host: String)
    case ShutdownCluster() =>  shutdownCluster(sender)
    case CollectClusterActorInfo() =>
      val sdr = sender
      collectClusterActorInfo().andThen { case Success(x) =>
        sdr ! ResponseData("success","成功获取集群信息", x.getClusterInfo())
      }
  }
  /**
   * 注册角色节点
   */
  def registerRoleMember(member: Member){
    //httpserver
    operaAfterRoleMemberUp(member, RoleType.HTTP_SERVER,(x,rt) => {
      val hostPortKey = member.address.host.get + ":" + member.address.port.get
      Master.haDataStorager ! AddRole(hostPortKey, x, rt)
      this.httpServerRef = x
      //注册
      log.info(s"注册新角色${rt}节点")
      if(this.isActiveMember)
        this.httpServerRef ! SwitchActiveMaster(
            )
    })
   //worker
    operaAfterRoleMemberUp(member, RoleType.WORKER,(ar,rt) => {
      val hostPortKey = member.address.host.get + ":" + member.address.port.get
      Master.haDataStorager ! AddRole(hostPortKey, ar, rt)
      context watch ar
      workers = workers :+ ar
      log.info(s"注册新角色${rt}节点，已注册数量: ${workers.size}, 当前注册${RoleType.WORKER}:节点路径：${ar}")
  	  if(status == R_INITED && isActiveMember) {
  		  startDaemons()
  	  }
    })
    //另一个Master启动
    operaAfterRoleMemberUp(member,RoleType.MASTER,(x,rt) => {
      if(x != self){
			  this.otherMaster = x
		    log.info(s"注册新角色${rt}节点, 进行监控, 节点路径:$x")
		    context.watch(x)
		  }
    })
  }

  /**
   * 请求得到新的worker，动态分配
   */
  private def allocateWorker(host: String):Option[ActorRef] = {
    if(workers.size > 0) {
      //host为-1情况下，随机分配
      if(host == "-1") {
        Some(workers(Random.nextInt(workers.size)))
      }else{ //指定host分配
      	val list = workers.filter { _.path.address.host.get == host }.toList
      	if(list.size > 0)
      	  Some(workers(Random.nextInt(list.size))) else None
      }
    }else{
      None
    }
  }
  /**
   * 作为活动主节点启动
   */
  def active():Future[Boolean] = {
    //准备集群环境
    def prepareMasterEnv(): Future[Boolean] = {
      //是否集群中存在活动的主节点
      //需要同步
      val rolesF = (Master.haDataStorager ? GetRoles()).mapTo[Map[String, RoleContent]]
      val isMExistedF = rolesF.map{mp =>
        val mastActOpt = mp.find{case (x,y) => y.roleType == RoleType.MASTER_ACTIVE}
        mastActOpt
      }.map{ maOpt =>
        //存在但并且不等于本actor
        if (maOpt.isDefined && maOpt.get._2.sdr != self){
          this.otherMaster = maOpt.get._2.sdr
    		  true
        }else{
          false
        }
      }
      //需要同步
      //把其他master设置为备份主节点
      val stbyF = (this.otherMaster ? StartIfActive(false)).mapTo[Boolean]
      //通知workers杀死所有的子actionactor
      val klF = (this.otherMaster ? KillAllActionActor()).mapTo[List[Boolean]].map { l =>
        if(l.filter { !_ }.size > 0)false else true
      }

      isMExistedF.flatMap { case isExi =>
        if(isExi){
          stbyF.flatMap { x =>
            if(!x) throw new Exception("设置其他master备份节点失败")
            klF
          }
        }else{
          Future{true}
        }
      }
    }
    /**
     * 获取集群分布数据
     */
    def getDData():Future[DistributeData] = {
      for {
        wfs <- (Master.haDataStorager ? GetWorkflows()).mapTo[List[WorkflowInfo]]
        rWFIs <- (Master.haDataStorager ? GetRWFIs()).mapTo[List[WorkflowInstance]]
  	    wWFIs <- (Master.haDataStorager ? GetWWFIs()).mapTo[List[WorkflowInstance]]
  	    xmlFiles <- (Master.haDataStorager ? GetXmlFiles()).mapTo[Map[String,Long]]
      } yield DistributeData(wfs,rWFIs,wWFIs,xmlFiles)
    }
    this.status = R_INITING
    val isPreparedF = prepareMasterEnv()
    Await.result(isPreparedF, 20 seconds)

    val config = context.system.settings.config
    //mysql持久化参数配置
    val mysqlConfig = (config.getString("workflow.mysql.user"),
                      config.getString("workflow.mysql.password"),
                      config.getString("workflow.mysql.jdbc-url"),
                      config.getBoolean("workflow.mysql.is-enabled")
                    )
    //Email参数配置
   val isHasPort = config.hasPath("workflow.email.smtp-port")
   val emailConfig = (config.getString("workflow.email.hostname"),
                      if(isHasPort) Some(config.getInt("workflow.email.smtp-port")) else None,
                      config.getBoolean("workflow.email.auth"),
                      config.getString("workflow.email.account"),
                      config.getString("workflow.email.password"),
                      config.getString("workflow.email.charset"),
                      config.getBoolean("workflow.email.is-enabled")
                    )
   //xmlLoader参数配置
   val xmlLoaderConfig = (config.getString("workflow.xml-loader.workflow-dir"),
                      config.getInt("workflow.xml-loader.scan-interval")
                    )
    this.isActiveMember = true
    //获取distributed数据来构建子actors
    val ddataF = getDData()
    ddataF.map{
      case DistributeData(wfs,rwfis, wwfis,xmlFiles) =>
        //创建持久化管理器
        //需要同步
        if(Master.persistManager == null){
        	Master.persistManager = context.actorOf(Props(PersistManager(mysqlConfig._3,mysqlConfig._1,mysqlConfig._2,mysqlConfig._4)),"pm")
        }
        //创建邮件发送器
        if(Master.emailSender == null){
        	Master.emailSender = context.actorOf(Props(EmailSender(emailConfig._1,emailConfig._2,emailConfig._3,emailConfig._4,emailConfig._5,emailConfig._6,emailConfig._7)),"mail-sender")
        }
        //创建日志记录器
        if(Master.logRecorder == null){
          Master.logRecorder = context.actorOf(Props(LogRecorder(mysqlConfig._3,mysqlConfig._1,mysqlConfig._2,mysqlConfig._4)),"log-recorder")
          LogRecorder.actor = Master.logRecorder
        }
        //创建xml装载器
        if(xmlLoader == null){
        	xmlLoader = context.actorOf(Props(XmlLoader(xmlLoaderConfig._1,xmlLoaderConfig._2, xmlFiles)),"xml-loader")
        }
        //创建workflow管理器
        if(workflowManager == null){
          rwfis.foreach { _.reset() }
          val allwwfis = rwfis ++ wwfis
          workflowManager = context.actorOf(Props(WorkFlowManager(wfs, allwwfis)),"wfm")
        }

        //通知http-server
        if(this.httpServerRef != null){
          this.httpServerRef ! SwitchActiveMaster()
        }
        //设置
        Master.haDataStorager ! AddRole(getHostPortKey(), self, RoleType.MASTER_ACTIVE)
        log.info(s"当前节点角色为${RoleType.MASTER_ACTIVE}，已启动成功")
        this.status = R_INITED

        //若存在worker，则启动
        if(workers.size > 0){
          startDaemons()
        }
		    true
    }
  }
  /**
   * 启动已经准备好的actors（必须为活动主节点）
   */
  private def startDaemons(){
    	this.status = R_STARTED
			workflowManager ! Start()
			xmlLoader ! Start()
			log.info("开始运行...")
  }
  /**
   * 设置为standby角色
   */
  def standby():Future[Boolean] = {
	  this.status = R_PREPARE
	  this.isActiveMember = false
    val rF1 = if(xmlLoader != null) (xmlLoader ? Stop()).mapTo[Boolean] else Future{true}
    var rF3 = if(workflowManager != null) (workflowManager ? Stop()).mapTo[Boolean] else Future{true}
    val list = List(rF1, rF3)
    val rF = Future.sequence(list).map { x =>
      this.xmlLoader = null
      this.workflowManager = null
      if(x.filter { !_ }.size > 0) false else true
    }
    rF.map{x =>
      Master.haDataStorager ! AddRole(getHostPortKey(), self, RoleType.MASTER_STANDBY)
      log.info(s"当前节点角色为${RoleType.MASTER_STANDBY}，已启动成功")
      x
    }
    rF
  }
  /**
   * 收集集群actor信息
   */
  def collectClusterActorInfo(): Future[ActorInfo] = {
    var allActorInfo = new ActorInfo()
    allActorInfo.name = "top"
    allActorInfo.atype = ROLE
    //获取本master的信息
    val maiF = (self ? CollectActorInfo()).mapTo[ActorInfo]
    //获取另外备份master的信息
    val omaiF = if(otherMaster != null){
      (otherMaster ? CollectActorInfo()).mapTo[ActorInfo]
    }else{
      null
    }
    //获取worker的actor信息
    val waisF = this.workers.map { x => (x ? CollectActorInfo()).mapTo[ActorInfo]}.toList

    val allAIsF = if(omaiF == null){
      waisF ++ (maiF  :: Nil)
    }else{
      waisF ++ (maiF :: omaiF :: Nil)
    }
    val allAiF = Future.sequence(allAIsF).map{ list =>
      allActorInfo.subActors = allActorInfo.subActors ++ list
      allActorInfo
    }
    allAiF
  }
  /**
   * 关闭集群
   */
  def shutdownCluster(sdr: ActorRef) = {
     if(xmlLoader!=null) xmlLoader ! Stop()
     if(workflowManager!=null){
       val result = (workflowManager ? KllAllWorkFlow()).mapTo[ResponseData]
       result.andThen{
       case Success(x) =>
        workers.foreach { _ ! ShutdownCluster() }
        sdr ! ResponseData("success","worker角色与master角色已关闭",null)
        if(otherMaster != null){
          otherMaster ! ShutdownCluster()
        }
        context.system.terminate()
      }
     }else{
       context.system.terminate()
     }

  }
}
object Master extends App{
  def apply(isActiveMember: Boolean) = new Master(isActiveMember)
  var persistManager:ActorRef = _
  var emailSender: ActorRef = _
  var logRecorder: ActorRef = _
  var haDataStorager: ActorRef = _

  startUp()
  /**
   * 作为活动主节点启动（若已存在活动主节点，则把该节点设置为备份主节点）
   */
  def startUp() = {
      val defaultConf = ConfigFactory.load()
      val masterConf = defaultConf.getString("workflow.nodes.master").split(":")
      val hostConf = "akka.remote.netty.tcp.hostname=" + masterConf(0)
      val portConf = "akka.remote.netty.tcp.port=" + masterConf(1)

      // 创建一个Config对象
      val config = ConfigFactory.parseString(portConf)
          .withFallback(ConfigFactory.parseString(hostConf))
          .withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [${RoleType.MASTER}]"))
          .withFallback(defaultConf)
      // 创建一个ActorSystem实例
      val system = ActorSystem("akkaflow", config)
      val master = system.actorOf(Props(Master(true)), name = RoleType.MASTER)
  }

}
