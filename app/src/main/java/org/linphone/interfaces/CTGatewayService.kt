package org.linphone.interfaces

import io.reactivex.rxjava3.core.Observable
import okhttp3.RequestBody
import org.linphone.models.TenantBrandingDefinition
import org.linphone.models.UserDevice
import org.linphone.models.UserInfo
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.usergroup.UserGroupModel
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CTGatewayService {
    @GET("api/v1.0/users/{userID}/devices?manufacturer=Softphones&model=UCM&model=KZSM")
    fun doGetUserDevices(
        @Path("userID") userID: String?
    ): Call<List<UserDevice>>

    @GET("api/v1.0/users/me")
    suspend fun getUserInfo(): Response<UserInfo>

    @GET("api/v1.0/users")
    fun doGetAllUsers(): Call<List<UserInfo>>

    @GET("api/v1.0/users/me/branding")
    fun doGetUserBranding(): Call<TenantBrandingDefinition>

    @POST("api/v1.0/clientdiagnostics/{fileName}")
    suspend fun postClientDiagnostics(
        @Path("fileName") fileName: String,
        @Body body: RequestBody
    ): Response<Void>

    @GET("api/v1.0/contactdirectories/users/me")
    fun doGetContactDirectories(): Call<List<ContactDirectoryModel>>

    @GET("api/v1.0/contactdirectories/{directoryId}/items")
    fun doSearchDirectory(
        @Path("directoryId") directoryId: String,
        @Query("filter") filter: String,
        @Query("maxItems") maxItems: Int = 100

    ): Observable<List<ContactItemModel>>

    @GET("api/v1.0/personalusergroups?includeContacts=true")
    fun doGetPersonalUserGroups(): Call<List<UserGroupModel>>

    @GET("api/v1.0/tenantusergroups")
    fun doGetTenantUserGroups(): Call<List<UserGroupModel>>
}
