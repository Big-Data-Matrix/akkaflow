package com.big.util

import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.FileWriter
import scala.util.matching.Regex

object FileUtil {
  /**
	 * 得到特定目录下指定扩展名的文件集合
	 *
	 */
	 def listFilesWithExtensions(file: File, extensions: List[String]): List[File] = {
     var fileList: List[File] = List()
	   if(file != null){
	     if(file.isDirectory()){
	       fileList = fileList ++ file.listFiles().flatMap { listFilesWithExtensions(_, extensions) }.toList
	     }else{
	       if(checkFileExtension(file, extensions)) fileList = fileList:+file
	     }
	   }
     fileList
	 }
  /**
	  * 检出指定文件是否扩展名为extensions
	  * @param file
	  * @param extension
	  * @return
	  */
	 def checkFileExtension(file: File, extensions: List[String]):Boolean = {
		 if ((file.getName() != null) && (file.getName().length() > 0)) {
       val i = file.getName().lastIndexOf('.');
       if (i > -1 && i < file.length() - 1) {
         val size = extensions.filter { file.getName().substring(i + 1) == _ }.size
         if(size > 0) return true
       }
		 }
		 return false;
	 }
	/**
   * 模糊匹配处理
   */
  def fileNameFuzzyMatch(fileName: String): Regex = {
    var name = fileName.replaceAll("\\.", "\\\\.")
    name = name.replaceAll("\\*", "(.\\*?)")
    ("^"+name+"$").r
  }

  	/**
  	 * 从指定路径中解析出文件名
  	 */
  	def getFileName(path: String): String = {
      val f = new File(path)
      f.getName
    }
  	/**
  	 * 从指定路径中解析目录与文件名
  	 */
  	def getDirAndBaseName(path: String):Tuple2[String, String] = {
  	  val idx = path.lastIndexOf("/")
  	  (path.substring(0, idx), path.substring(idx+1))
  	}
	/**
	 * 读取文件
	 */
	def readFile(fileName: String):Array[Byte] = readFile(new File(fileName))
	/**
	 * 读取文件
	 */
  def readFile(file: File):Array[Byte] = {
    val is = new FileInputStream(file)
    val os = new ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    var n = 0
    do {
      n = is.read(buffer, 0, buffer.length)
      if(n != -1) os.write(buffer, 0, n)
    } while(n != -1)
    is.close()
    return os.toByteArray()
  }
  /**
   * 覆盖写入文件(字节流)
   */
  def writeFile(path: String, content:Array[Byte]) = {
     val f = new File(path)
     f.delete()
     val fos = new FileOutputStream(path)
     fos.write(content)
     fos.flush()
     fos.close()
  }
  /**
   * 写入文件（文本）,默认为覆盖
   */
  def writeFile(path: String,lines:List[String])(isAppend:Boolean) = {
		val fw = new FileWriter(new File(path), isAppend);
    val writer = new PrintWriter(fw)
    lines.foreach { x => writer.write(x+"\n") }
    writer.flush()
    fw.flush()
    writer.close()
    fw.close()
  }

  /**
   * 设置文件执行状态
   */
  def setExecutable(path: String, isExecutable: Boolean):Boolean = {
    val f = new File(path)
    if(f.exists()){
      f.setExecutable(isExecutable)
      true
    }else{
      false
    }
  }
  /**
   * 递归删除目录，或指定文件
   */
	 def deleteDirOrFile(dir: File):Boolean = {
	      if(dir == null)
	        return true
	      else if(dir.isDirectory()) {
            val children = dir.list().foreach { x =>
              val rs = deleteDirOrFile(new File(dir, x))
              if(!rs)  return false
            }
        }
        // 目录此时为空，可以删除
        return dir.delete()
    }
}
