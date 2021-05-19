import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import java.util
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.sql._
import org.apache.spark.sql.execution.datasources.jdbc._
import org.apache.spark.sql.functions.{udf, col}
import org.apache.spark.sql.functions._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory, Point}
import com.vividsolutions.jts.io.WKBWriter



object Kafka_test {
    def main (args: Array[String]) {
	 val gf = new GeometryFactory

   def getPoint(x: Double, y: Double): String = {
     val wkbWriter = new WKBWriter(2, true)
     try {
       val p = gf.createPoint(new Coordinate(x, y))
       p.setSRID(4326)
       WKBWriter.toHex(wkbWriter.write(p))
     } catch {
        case e: Exception =>
        val p = gf.createPoint(new Coordinate(0.0, 0.0))
        p.setSRID(4326)
        WKBWriter.toHex(wkbWriter.write(p))
    	}
	  }

	val spark = SparkSession.builder().getOrCreate()
        val ssc = new StreamingContext(spark.sparkContext, Seconds(20))
        val topics = Array("2part_test")
	val getGeom = udf[String, Double, Double](getPoint)

	val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "192.168.122.12:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "1",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )
	import spark.implicits._

	val kafkaStream = 
	  KafkaUtils.createDirectStream(
 	  ssc,
          LocationStrategies.PreferConsistent,
          ConsumerStrategies.Subscribe[String, String](
          topics,
	  kafkaParams
        )
      )
	val gpParams = Map(
      "url" -> spark.conf.get("spark.gp.jdbc.url"), //"jdbc:postgresql://192.168.122.12/databasekirill",
      "user" -> spark.conf.get("spark.gp.jdbc.user"), //"kirill_test",
      "password" -> spark.conf.get("spark.gp.jdbc.password"), //"",
      "dbtable" -> spark.conf.get("spark.gp.jdbc.table"), //"table_test"
      "server.path" -> "/usr/local/greenplum-db-6.9.0/bin/gpfdist"
    )	

	

	var sum_rdd: Long = 0
	kafkaStream.foreachRDD(rdd => {
	if (!rdd.isEmpty()) {
	println("---read batch: "+java.time.LocalDateTime.now)
	var count_rdd: Long = rdd.count()
	println("*** got an RDD, size = " + count_rdd)
	sum_rdd += count_rdd
	println("sum rdd= "+sum_rdd)


	val df = spark.read.json(rdd.map(record => record.value()))
                .withColumn("records", explode($"records"))
                .withColumn("gps_point", getGeom(col("records.subrecord.subrecordData.longitude"), 
                    col("records.subrecord.subrecordData.latitude")))
                .withColumn("object_id", $"records.objectId")
                .withColumn("id", expr("uuid()"))
                .withColumn("speed", $"records.subrecord.subrecordData.speed")
                .withColumn("direction", $"records.subrecord.subrecordData.direction")
                .withColumn("navigation_time", $"records.subrecord.subrecordData.navigationTime")
                .withColumn("mv", $"records.subrecord.subrecordData.mv")
                .select("id", "object_id", "gps_point", "speed", "direction", "navigation_time", "mv")
	df.printSchema()
	println("---data ready for write"+java.time.LocalDateTime.now)
	df.write.format("greenplum").options(gpParams).mode(SaveMode.Append).save()
	println("---batch has written: "+java.time.LocalDateTime.now)

	//if (sum_rdd>100000) {ssc.stop()}		
	}
	else {
	println("***************here is not rdd*************")
	     }   
        })
 

	ssc.start()
	println("************start ssc: "+java.time.LocalDateTime.now)
        ssc.awaitTermination()
	//ssc.stop()       
       		
    }
}

