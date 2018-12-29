package com.big.pub

import akka.actor.{Actor, ActorLogging}
import akka.util.Timeout

/**
  * <p>
  *     actor扩展类，这里可以重点学习下with语法，以及scala的类继承机制
  * </p>
  */
class ActorTool extends Actor with ActorLogging{

    import com.big.pub.ActorTool._

    implicit val timeout = Timeout(20 seconds)

    def receive: Actor.Receive = indivivalReceive orELse commonReceice

    def indivivalReceive: Actor.Receive

    def commonReceice: Actor.Receive = {

    }

    def collectActorInfo():Future[ActorInfo] = {
        val ai = new ActorInfo()
        ai.name = s"${self.path.name}"
        ai.atype = this.actorType
        val caiFs = context.children.map({ child => (child ? CollectActorInfo())})
    }

}

object ActorTool {
    object ActorType extends Enumeration{
        type ActorType = Value
        val ROLE,DEAMO,ACTOR = Value
    }

    class ActorInfo extends Serializable{
        import com.big.pub.ActorTool.ActorType._

        var name: String = _
        var  ip: String  = _
        var port: Int = _
        var atype: ActorType = ACTOR
        var subActors:List[ActorInfo] = List()

        def getClusterInfo():List[Map[String, String]] = {
            var l:List[]
        }

    }
}
