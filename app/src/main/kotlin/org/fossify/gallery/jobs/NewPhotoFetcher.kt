package org.fossify.gallery.jobs

import android.app.job.JobInfo
import android.app.job.JobInfo.TriggerContentUri
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.addPathToDB
import org.fossify.gallery.extensions.updateDirectoryPath

// based on https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri)
class NewPhotoFetcher : JobService() {
    companion object {
        const val PHOTO_VIDEO_CONTENT_JOB = 1
        private val MEDIA_URI = Uri.parse("content://${MediaStore.AUTHORITY}/")
        private val PHOTO_PATH_SEGMENTS = Images.Media.EXTERNAL_CONTENT_URI.pathSegments
        private val VIDEO_PATH_SEGMENTS = Video.Media.EXTERNAL_CONTENT_URI.pathSegments
    }

    private val mHandler = Handler()
    private val mWorker = Runnable {
        scheduleJob(this@NewPhotoFetcher)
        jobFinished(mRunningParams, false)
    }

    private var mRunningParams: JobParameters? = null

    fun scheduleJob(context: Context) {
        val componentName = ComponentName(context, NewPhotoFetcher::class.java)
        val photoUri = Images.Media.EXTERNAL_CONTENT_URI
        val videoUri = Video.Media.EXTERNAL_CONTENT_URI
        JobInfo.Builder(PHOTO_VIDEO_CONTENT_JOB, componentName).apply {
            addTriggerContentUri(TriggerContentUri(photoUri, TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            addTriggerContentUri(TriggerContentUri(videoUri, TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            addTriggerContentUri(TriggerContentUri(MEDIA_URI, 0))

            try {
                context.getSystemService(JobScheduler::class.java)?.schedule(build())
            } catch (ignored: Exception) {
            }
        }
    }

    fun isScheduled(context: Context): Boolean {
        val jobScheduler = context.getSystemService(JobScheduler::class.java)
        val jobs = jobScheduler.allPendingJobs
        return jobs.any { it.id == PHOTO_VIDEO_CONTENT_JOB }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        mRunningParams = params
        ensureBackgroundThread {
            try {
                getAffectedFolderPaths(params).forEach {
                    updateDirectoryPath(it)
                }
            } finally {
                mHandler.post(mWorker)
            }
        }

        return true
    }

    private fun getAffectedFolderPaths(params: JobParameters): HashSet<String> {
        val affectedFolderPaths = HashSet<String>()
        val triggeredUris = params.triggeredContentUris ?: return affectedFolderPaths
        if (params.triggeredContentAuthorities == null) {
            return affectedFolderPaths
        }

        val ids = getTriggeredIds(triggeredUris)
        if (ids.isEmpty()) {
            return affectedFolderPaths
        }

        val selection = getMediaSelection(ids)
        val projection = arrayOf(Images.ImageColumns.DATA)
        val uris = arrayListOf(Images.Media.EXTERNAL_CONTENT_URI, Video.Media.EXTERNAL_CONTENT_URI)
        uris.forEach { uri ->
            addAffectedPaths(uri, projection, selection, affectedFolderPaths)
        }

        return affectedFolderPaths
    }

    private fun getTriggeredIds(uris: Array<Uri>): ArrayList<String> {
        val ids = arrayListOf<String>()
        for (uri in uris) {
            val path = uri.pathSegments ?: continue
            val isPhoto = path.size == PHOTO_PATH_SEGMENTS.size + 1
            val isVideo = path.size == VIDEO_PATH_SEGMENTS.size + 1
            if (isPhoto || isVideo) {
                ids.add(path[path.lastIndex])
            }
        }
        return ids
    }

    private fun getMediaSelection(ids: ArrayList<String>): String {
        val selection = StringBuilder()
        for (id in ids) {
            if (selection.isNotEmpty()) {
                selection.append(" OR ")
            }
            selection.append("${Images.ImageColumns._ID} = '$id'")
        }
        return selection.toString()
    }

    private fun addAffectedPaths(uri: Uri, projection: Array<String>, selection: String, into: HashSet<String>) {
        try {
            contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getStringValue(Images.ImageColumns.DATA)
                    into.add(path.getParentPath())
                    addPathToDB(path)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        mHandler.removeCallbacks(mWorker)
        return false
    }
}
