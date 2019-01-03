package com.big.test

import com.kent.coordinate.ParamHandler

object ParamReplaceTest extends App{
  val str = """
    ${param:stime}
		str="'"${line//,/\',\'}"'"
		${param:stime}
    """

  val para = ParamHandler().getValue(str, Map("stime" -> "2016-04-03"))
  println(para)
}
