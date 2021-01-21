package offline

import org.apache.spark.SparkConf
import org.apache.spark.mllib.recommendation.{ALS, Rating}
import org.apache.spark.sql.SparkSession

case class Movie(val mid:Int, val name:String, val descri:String, val timelong:String,
                 val issue:String, val shoot:String, val language:String, val genres:String,
                 val actors:String, val directors:String)
case class MovieRating(val uid:Int, val mid:Int, val score:Double, val timestamp: Int)
case class MongoConfig(val uri:String, val db:String)

case class Recommendation(rid:Int, r:Double)
case class UserRecs(uid:Int, recs:Seq[Recommendation])
case class MovieRecs(uid:Int, recs:Seq[Recommendation])

object OfflineRecommender {

  val MONGODB_MOVIE_COLLECTION = "Movie"
  val MONGODB_RATING_COLLECTION = "Rating"

  val USER_MAX_RECOMMENDATION = 20
  val USER_RECS = "UserRecs"

  def main(args: Array[String]): Unit = {

    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://movie:27017/recommender",
      "mongo.db" -> "recommender"
    )

    val sparkConf = new SparkConf().setAppName("OfflineRecommender").setMaster(config("spark.cores"))
      .set("spark.executor.memory","6G").set("spark.driver.memory","3G")

    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    val mongoConfig = MongoConfig(config("mongo.uri"),config("mongo.db"))

    import spark.implicits._

    val ratingRDD = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[MovieRating]
      .rdd
      .map(rating => (rating.uid, rating.mid, rating.score))

    val userRDD = ratingRDD.map(_._1).distinct()

    val movieRDD = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_MOVIE_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Movie]
      .rdd
      .map(_.mid)

    //创建训练数据集
    val trainData = ratingRDD.map(x => Rating(x._1,x._2,x._3))
    val (rank,iterations,lambda) = (50,10,0.01)

    //训练ALS模型
    val model = ALS.train(trainData,rank,iterations,lambda)

    //构造一个usersProducts  RDD[(Int,Int)]
    val userMovies = userRDD.cartesian(movieRDD)

    val preRatings = model.predict(userMovies)

    val userRecs = preRatings.map(rating =>(rating.user,(rating.product, rating.rating)))
        .groupByKey()
        .map{
          case (uid,recs) => UserRecs(uid,recs.toList.sortWith(_._2 > _._2).take(USER_MAX_RECOMMENDATION).map(x => Recommendation(x._1,x._2)))
        }.toDF()

    userRecs
      .write
      .option("uri",mongoConfig.uri)
      .option("collection",USER_RECS)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()



    spark.close()
  }

}
