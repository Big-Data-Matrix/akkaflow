package com.big.pub

import java.io._


/**
  * <p>
  *    深度克隆特质
  * </p>
  *
  * @tparam A
  */
trait DeepCloneable[A] extends Serializable{

    def deepCloneAs[B <: A]():B = {
        var outer: Any = null
        try{
            val baos = new ByteArrayOutputStream()
            val oos = new ObjectOutputStream(baos)
            oos.writeObject(this)
            val bais = new ByteArrayInputStream(baos.toByteArray)
            val ois = new ObjectInputStream(bais)
            outer = ois.readObject()
        }catch {
            case e:IOException => e.printStackTrace()
            case e:Exception => e.printStackTrace()
        }
        outer.asInstanceOf[B]
    }

}
