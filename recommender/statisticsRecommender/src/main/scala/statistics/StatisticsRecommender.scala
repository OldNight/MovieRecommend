package statistics

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

case class Movie(val mid:Int, val name:String, val descri:String, val timelong:String,
                 val issue:String, val shoot:String, val language:String, val genres:String,
                 val actors:String, val directors:String)
case class Rating(val uid:Int, val mid:Int, val score:Double, val timestamp: Int)
case class MongoConfig(val uri:String, val db:String)

/**
  * 推荐对象
  * @param rid  推荐的movie的mid
  * @param r    movie的评分
  */
case class Recommendation(rid:Int, r:Double)

/**
  * 电影类别的推荐
  * @param genres  电影的类被
  * @param recs    top10的电影的集合
  */
case class GenresRecommendation(genres:String, recs:Seq[Recommendation])

object StatisticsRecommender {

  val MONGODB_MOVIE_COLLECTION = "Movie"
  val MONGODB_RATING_COLLECTION = "Rating"

  val RATE_MORE_MOVIES = "RateMoreMovies"
  val RATE_MORE_RECENTLY_MOVIES = "RateMoreRecentlyMovies"
  val AVERAGE_MOVIES = "AverageMovies"
  val GENRES_TOP_MOVIES = "GenresTopMovies"

  def main(args: Array[String]): Unit = {

    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://movie:27017/recommender",
      "mongo.db" -> "recommender"
    )

    val sparkConf = new SparkConf().setAppName("StatisticsRecommender").setMaster(config("spark.cores"))
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    val mongoConfig = MongoConfig(config("mongo.uri"),config("mongo.db"))

    import spark.implicits._

    val ratingDF = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Rating]
      .toDF()

    val movieDF = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_MOVIE_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Movie]
      .toDF()

    ratingDF.createOrReplaceTempView("ratings")

    //统计所有历史数据中每个电影的评分数
    //数据结构：mid，count
    val rateMoreMoviesDF = spark.sql("select mid, count(mid) as count from ratings group by mid")
    rateMoreMoviesDF
      .write
      .option("uri",mongoConfig.uri)
      .option("collection",RATE_MORE_MOVIES)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //统计以月为单位每个电影的平分数
    //数据结构：：mid,count,time
    //创建一个日期格式化工具
    val simpleDateFormat = new SimpleDateFormat("yyyyMM")
    //注册一个udf函数，用于将timestamp转换为年月格式
    spark.udf.register("changeDate",(x:Int) => simpleDateFormat.format(new Date(x * 1000L)).toInt)

    val ratingOfYearMouth = spark.sql("select mid, score, changeDate(timestamp) as yearmouth from ratings")

    ratingOfYearMouth.createOrReplaceTempView("ratingOfMouth")

    val rateMoreRecentlyMovies = spark.sql("select mid, count(mid) as count, yearmouth from ratingOfMouth group by yearmouth,mid")

    rateMoreRecentlyMovies
      .write
      .option("uri",mongoConfig.uri)
      .option("collection",RATE_MORE_RECENTLY_MOVIES)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //统计每个电影的平均评分
    val averageMoviesDF = spark.sql("select mid, avg(score) as avg from ratings group by mid")

    averageMoviesDF
      .write
      .option("uri",mongoConfig.uri)
      .option("collection",AVERAGE_MOVIES)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //统计每种电影类型中评分最高的十个电影
    val movieWithScore = movieDF.join(averageMoviesDF,Seq("mid","mid"))

    val genres = List("Action","Adventure","Animation","Comedy","Ccrime","Documentary","Drama","Family","Fantasy","Foreign","History","Horror","Music","Mystery"
      ,"Romance","Science","Tv","Thriller","War","Western")

    val genresRDD = spark.sparkContext.makeRDD(genres)

    val genrenTopMovies = genresRDD.cartesian(movieWithScore.rdd)//将电影类别和电影数据进行笛卡尔积操作
        .filter{
          //过滤掉电影的类别不匹配的电影
          case (genres, row) => row.getAs[String]("genres").toLowerCase.contains(genres.toLowerCase)
        }
        .map{
          //将整个数据集的数据量减小，生成RDD[String,Iter[mid,avg]]
          case (genres, row) => {
            (genres,(row.getAs[Int]("mid"),row.getAs[Double]("avg")))
          }
        }.groupByKey()//将genres数据集中相同的聚集
        .map{
        //通过评分的大小进行数据的排序，然后将数据映射为对象
          case (genres, items) => GenresRecommendation(genres,items.toList.sortWith(_._2 > _._2).take(10).map(item => Recommendation(item._1,item._2)))
        }.toDF()

    genrenTopMovies
      .write
      .option("uri",mongoConfig.uri)
      .option("collection",GENRES_TOP_MOVIES)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()


    spark.stop()

  }
}
