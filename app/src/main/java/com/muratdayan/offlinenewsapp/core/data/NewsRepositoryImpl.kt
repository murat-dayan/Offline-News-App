package com.muratdayan.offlinenewsapp.core.data

import com.muratdayan.offlinenewsapp.BuildConfig
import com.muratdayan.offlinenewsapp.core.data.local.ArticlesDao
import com.muratdayan.offlinenewsapp.core.data.remote.NewsListDto
import com.muratdayan.offlinenewsapp.core.domain.Article
import com.muratdayan.offlinenewsapp.core.domain.NewsList
import com.muratdayan.offlinenewsapp.core.domain.NewsRepository
import com.muratdayan.offlinenewsapp.core.domain.NewsResult
import kotlinx.coroutines.flow.Flow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

class NewsRepositoryImpl(
    private val httpClient: HttpClient,
    private val dao: ArticlesDao
) : NewsRepository {

    private val tag = "NewsRepository: "
    private val baseUrl = "https://newsdata.io/api/1/latest"
    private val apiKey = BuildConfig.API_KEY

    private suspend fun getLocalNews(nextPage: String?): NewsList {
        val localNews = dao.getArticleList()
        println(tag + "getLocalNews" + localNews.size + "nextpage" + nextPage)

        val newsList = NewsList(
            articles = localNews.map { it.toArticle() },
            nextPage = nextPage
        )

        return newsList
    }

    private suspend fun getRemoteNews(nextPage: String?): NewsList{
        val newsListDto : NewsListDto = httpClient.get(baseUrl){
            parameter("apikey",apiKey)
            parameter("language","en")
            if(nextPage != null) parameter("page",nextPage)
        }.body()

        println(tag + "getRemoteNews" + newsListDto.results?.size + "nextpage" + nextPage)

        return newsListDto.toNewsList()
    }

    override suspend fun getNews(): Flow<NewsResult<NewsList>> {
        return flow {
            val remoteNewsList = try {
                getRemoteNews(null)
            }catch (e:Exception){
                e.printStackTrace()
                if(e is CancellationException) throw e
                println(tag + "getNews remote exception: " + e.message)
                null
            }

            remoteNewsList?.let {
                dao.clearDatabase()
                dao.upsertArticleList(remoteNewsList.articles.map { it.toArticleEntity() })
                emit(NewsResult.Success(getLocalNews(remoteNewsList.nextPage)))
                return@flow
            }

            val localNewsList = getLocalNews(null)
            if (localNewsList.articles.isNotEmpty()){
                emit(NewsResult.Success(localNewsList))
                return@flow
            }

            emit(NewsResult.Error("No Data"))
        }
    }

    override suspend fun paginate(nextPage: String?): Flow<NewsResult<NewsList>> {
        return flow {
            val remoteNewsList = try {
                getRemoteNews(nextPage)
            }catch (e:Exception){
                e.printStackTrace()
                if(e is CancellationException) throw e
                println(tag + "paginate remote exception: " + e.message)
                null
            }

            remoteNewsList?.let {
                dao.upsertArticleList(remoteNewsList.articles.map { it.toArticleEntity() })
                emit(NewsResult.Success(remoteNewsList))
                return@flow
            }

        }
    }

    override suspend fun getArticle(articleId: String): Flow<NewsResult<Article>> {
        return flow {
            dao.getArticle(articleId)?.let {article->
                println(tag + "getArticle" + article.article_id)
                emit(NewsResult.Success(article.toArticle()))
                return@flow
            }

            try {
                val remoteArticle : NewsListDto= httpClient.get(baseUrl){
                    parameter("apikey",apiKey)
                    parameter("id",articleId)
                }.body()
                println(tag + "getArticle" + remoteArticle.results?.size)

                if (remoteArticle.results?.isNotEmpty() == true){
                    emit(NewsResult.Success(remoteArticle.results[0].toArticle()))
                }else{
                    emit(NewsResult.Error("No Data Article "))
                }

            }catch (e:Exception){
                e.printStackTrace()
                if(e is CancellationException) throw e
                println(tag + "getArticle exception: " + e.message)
                emit(NewsResult.Error("No Data Article "))
            }
        }
    }

}