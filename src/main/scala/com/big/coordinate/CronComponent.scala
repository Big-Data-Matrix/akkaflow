package com.big.coordinate

import java.util.Date

import com.big.pub.DeepCloneable
import cronish._
import cronish.dsl._
import java.util.Date

import cronish._
import cronish.dsl._
import scalendar.Scalendar
import scalendar.toDate

class CronComponent(private var _cronStr: String, private var _sdate: Date, private var _edate: Date) extends DeepCloneable[CronComponent] {
    var nextExecuteTime: Date = _;
    def cronStr = _cronStr
    var sdate:Date = _sdate
    var edate: Date = _edate

    val pattern = """(?i)every""".r
    @transient val cron = if(pattern.findFirstIn(_cronStr).isEmpty){
        var elements = _cronStr.split(" ")
        Cron("0", elements(0), elements(1), elements(2), elements(3), elements(4), "*")
    } else {
        cronStr.cron
    }
    setNextExecuteTime()
    /**
      * 在当前时间下，下一次的执行时间
      */
    def setNextExecuteTime(): Boolean = {
        val now = new Date()
        if(now.getTime > _sdate.getTime && now.getTime < _edate.getTime){
            this.nextExecuteTime = cron.nextTime.date
            true
        }else{
            false
        }
    }
    /**
      * 当前是否处于执行时间范围前
      */
    def isBeforeTimeRange(): Boolean = {
        val now = new Date()
        if(now.getTime < _sdate.getTime) true else false
    }
    /**
      * 当前是否处于执行时间范围后
      */
    def isAfterTimeRange(): Boolean = {
        val now = new Date()
        if(now.getTime > _edate.getTime) true else false
    }
    /**
      * 是否可以执行了
      */
    def isAfterExecuteTime: Boolean = {
        val now = new Date()
        if(now.getTime > this.nextExecuteTime.getTime)true else false
    }

    override def deepClone: CronComponent = {
        val tmp = new CronComponent(cronStr, sdate, edate)
        tmp.nextExecuteTime = nextExecuteTime
        tmp
    }
}

object CronComponent{
    def apply(_cronStr: String, _sdate: Date, _edate: Date): CronComponent = {
        if(_cronStr != null) new CronComponent(_cronStr, _sdate, _edate) else null
    }

    def main(args: Array[String]): Unit = {
        val a = new CronComponent("Every day at midnight",Scalendar(2016,10, 11),Scalendar(2016,11, 11))
        //val a = CronComponent("50 20 * * *",Scalendar(2016,10, 11),Scalendar(2016,11, 11))
        //a.getNextExecuteTime().foreach { x => println(x.toLocaleString()) }
    }

}
