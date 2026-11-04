package com.example.campus.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Account-partitioned Room snapshots. Stale responses are visibly marked and never used to authorize mutations. */
class LiveSnapshotStore(private val context: Context) {
    private val preferences=context.getSharedPreferences("campus_live_account",Context.MODE_PRIVATE)
    data class Snapshot(val payload:String,val cached:Boolean,val updatedAt:Long) {
        fun obj()=JSONObject(payload)
        fun array()=JSONArray(payload)
    }
    fun bind(profile:JSONObject) {
        require(profile.optString("id").isNotBlank()) { "The server profile has no user ID." }
        preferences.edit().putString("owner", ServiceConfig(context).apiBaseUrl+"|"+profile.getString("id")).commit()
        save("/api/v1/profile",profile.toString())
    }
    fun accountId():String = owner().substringAfterLast("|")
    fun unbind() { preferences.edit().clear().commit() }
    private fun owner()=preferences.getString("owner",null) ?: throw ApiException(401,"Verify your university account before opening live campus services.")
    fun save(path:String,payload:String) {
        val entry=CachedSnapshot().apply{owner=owner();this.path=path;this.payload=payload;updatedAt=System.currentTimeMillis()}
        OfflineDatabase.get(context).snapshots().put(entry)
    }
    fun load(path:String,array:Boolean=false):Snapshot {
        val id=owner()
        try {
            val api=LiveApiClient(context)
            val data=if(array)api.requestArray(path).toString() else api.request("GET",path).toString()
            save(path,data)
            return Snapshot(data,false,System.currentTimeMillis())
        } catch(e:Exception) {
            if(e is ApiException && e.status in 400..499 && e.status !in listOf(408,429)) throw e
            val cached=OfflineDatabase.get(context).snapshots().get(id,path) ?: throw e
            return Snapshot(cached.payload,true,cached.updatedAt)
        }
    }
}
