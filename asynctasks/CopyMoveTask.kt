package com.simplemobiletools.commons.asynctasks

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.AsyncTask
import android.os.Handler
import android.provider.MediaStore
import android.support.v4.app.NotificationCompat
import android.support.v4.provider.DocumentFile
import android.support.v4.util.Pair
import com.simplemobiletools.commons.R
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.CONFLICT_OVERWRITE
import com.simplemobiletools.commons.helpers.CONFLICT_SKIP
import com.simplemobiletools.commons.helpers.OTG_PATH
import com.simplemobiletools.commons.helpers.isOreoPlus
import com.simplemobiletools.commons.interfaces.CopyMoveListener
import com.simplemobiletools.commons.models.FileDirItem
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.*

class CopyMoveTask(val activity: BaseSimpleActivity, val copyOnly: Boolean = false, val copyMediaOnly: Boolean, val conflictResolutions: LinkedHashMap<String, Int>,
                   listener: CopyMoveListener, val copyHidden: Boolean) : AsyncTask<Pair<ArrayList<FileDirItem>, String>, Void, Boolean>() {
    private val INITIAL_PROGRESS_DELAY = 3000L
    private val PROGRESS_RECHECK_INTERVAL = 500L

    private var mListener: WeakReference<CopyMoveListener>? = null
    private var mTransferredFiles = ArrayList<FileDirItem>()
    private var mDocuments = LinkedHashMap<String, DocumentFile?>()
    private var mFiles = ArrayList<FileDirItem>()
    private var mFileCountToCopy = 0
    private var mDestinationPath = ""

    // progress indication
    private var mNotificationManager: NotificationManager
    private var mNotificationBuilder: NotificationCompat.Builder
    private var mCurrFilename = ""
    private var mCurrentProgress = 0L
    private var mMaxSize = 0
    private var mNotifId = 0
    private var mProgressHandler = Handler()

    init {
        mListener = WeakReference(listener)
        mNotificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationBuilder = NotificationCompat.Builder(activity)
    }

    override fun doInBackground(vararg params: Pair<ArrayList<FileDirItem>, String>): Boolean? {
        if (params.isEmpty()) {
            return false
        }

        val pair = params[0]
        mFiles = pair.first!!
        mDestinationPath = pair.second!!
        mFileCountToCopy = mFiles.size
        mNotifId = (System.currentTimeMillis() / 1000).toInt()
        mMaxSize = 0
        for (file in mFiles) {
            if (file.size == 0L) {
                file.size = file.getProperSize(activity, copyHidden)
            }
            val newPath = "$mDestinationPath/${file.name}"
            val fileExists = if (newPath.startsWith(OTG_PATH)) activity.getFastDocumentFile(newPath)?.exists()
                    ?: false else File(newPath).exists()
            if (getConflictResolution(newPath) != CONFLICT_SKIP || !fileExists) {
                mMaxSize += (file.size / 1000).toInt()
            }
        }

        mProgressHandler.postDelayed({
            initProgressNotification()
            updateProgress()
        }, INITIAL_PROGRESS_DELAY)

        for (file in mFiles) {
            try {
                val newPath = "$mDestinationPath/${file.name}"
                val newFileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath(), file.isDirectory)
                if (activity.getDoesFilePathExist(newPath)) {
                    val resolution = getConflictResolution(newPath)
                    if (resolution == CONFLICT_SKIP) {
                        mFileCountToCopy--
                        continue
                    } else if (resolution == CONFLICT_OVERWRITE) {
                        newFileDirItem.isDirectory = if (File(newPath).exists()) File(newPath).isDirectory else activity.getSomeDocumentFile(newPath)!!.isDirectory
                        activity.deleteFileBg(newFileDirItem, true)
                    }
                }

                copy(file, newFileDirItem)
            } catch (e: Exception) {
                activity.toast(e.toString())
                return false
            }
        }

        if (!copyOnly) {
            activity.deleteFilesBg(mTransferredFiles) {}
        }

        return true
    }

    override fun onPostExecute(success: Boolean) {
        mProgressHandler.removeCallbacksAndMessages(null)
        mNotificationManager.cancel(mNotifId)
        val listener = mListener?.get() ?: return

        if (success) {
            listener.copySucceeded(copyOnly, mTransferredFiles.size >= mFileCountToCopy, mDestinationPath)
        } else {
            listener.copyFailed()
        }
    }

    private fun initProgressNotification() {
        val channelId = "copying_moving_channel"
        val title = activity.getString(if (copyOnly) R.string.copying else R.string.moving)
        if (isOreoPlus()) {
            val importance = NotificationManager.IMPORTANCE_LOW

        }

        mNotificationBuilder.setContentTitle(title)
                .setSmallIcon(R.drawable.ic_copy)
    }

    private fun updateProgress() {
        mNotificationBuilder.apply {
            setContentText(mCurrFilename)
            setProgress(mMaxSize, (mCurrentProgress / 1000).toInt(), false)
            mNotificationManager.notify(mNotifId, build())
        }

        mProgressHandler.removeCallbacksAndMessages(null)
        mProgressHandler.postDelayed({
            updateProgress()
        }, PROGRESS_RECHECK_INTERVAL)
    }

    private fun getConflictResolution(path: String): Int {
        return if (conflictResolutions.size == 1 && conflictResolutions.containsKey("")) {
            conflictResolutions[""]!!
        } else if (conflictResolutions.containsKey(path)) {
            conflictResolutions[path]!!
        } else {
            CONFLICT_SKIP
        }
    }

    private fun copy(source: FileDirItem, destination: FileDirItem) {
        if (source.isDirectory) {
            copyDirectory(source, destination.path)
        } else {
            copyFile(source, destination)
        }
    }

    private fun copyDirectory(source: FileDirItem, destinationPath: String) {
        if (!activity.createDirectorySync(destinationPath)) {
            val error = String.format(activity.getString(R.string.could_not_create_folder), destinationPath)
            activity.showErrorToast(error)
            return
        }

        if (source.path.startsWith(OTG_PATH)) {
            val children = activity.getDocumentFile(source.path)?.listFiles() ?: return
            for (child in children) {
                val newPath = "$destinationPath/${child.name}"
                if (activity.getDoesFilePathExist(newPath)) {
                    continue
                }

                val oldPath = "${source.path}/${child.name}"
                val oldFileDirItem = FileDirItem(oldPath, child.name, child.isDirectory, 0, child.length())
                val newFileDirItem = FileDirItem(newPath, child.name, child.isDirectory)
                copy(oldFileDirItem, newFileDirItem)
            }
            mTransferredFiles.add(source)
        } else {
            val children = File(source.path).list()
            for (child in children) {
                val newPath = "$destinationPath/$child"
                if (activity.getDoesFilePathExist(newPath)) {
                    continue
                }

                val oldFile = File(source.path, child)
                val oldFileDirItem = oldFile.toFileDirItem(activity.applicationContext)
                val newFileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath(), oldFile.isDirectory)
                copy(oldFileDirItem, newFileDirItem)
            }
            mTransferredFiles.add(source)
        }
    }

    private fun copyFile(source: FileDirItem, destination: FileDirItem) {
        if (copyMediaOnly && !source.path.isImageVideoGif()) {
            mCurrentProgress += source.size
            return
        }

        val directory = destination.getParentPath()
        if (!activity.createDirectorySync(directory)) {
            val error = String.format(activity.getString(R.string.could_not_create_folder), directory)
            activity.showErrorToast(error)
            mCurrentProgress += source.size
            return
        }

        mCurrFilename = source.name
        var inputStream: InputStream? = null
        var out: OutputStream? = null
        try {
            if (!mDocuments.containsKey(directory) && activity.needsStupidWritePermissions(destination.path)) {
                mDocuments[directory] = activity.getDocumentFile(directory)
            }
            out = activity.getFileOutputStreamSync(destination.path, source.path.getMimeType(), mDocuments[directory])
            inputStream = activity.getFileInputStreamSync(source.path)!!

            var copiedSize = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                out!!.write(buffer, 0, bytes)
                copiedSize += bytes
                mCurrentProgress += bytes
                bytes = inputStream.read(buffer)
            }

            if (source.size == copiedSize) {
                mTransferredFiles.add(source)
                activity.scanPath(destination.path) {
                    if (activity.baseConfig.keepLastModified) {
                        copyOldLastModified(source.path, destination.path)
                    }
                }
            }
        } catch (e: Exception) {
            activity.showErrorToast(e)
        } finally {
            inputStream?.close()
            out?.close()
        }
    }

    private fun copyOldLastModified(sourcePath: String, destinationPath: String) {
        val projection = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED)
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        var selectionArgs = arrayOf(sourcePath)
        val cursor = activity.applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)

        cursor?.use {
            if (cursor.moveToFirst()) {
                val dateTaken = cursor.getLongValue(MediaStore.Images.Media.DATE_TAKEN)
                val dateModified = cursor.getIntValue(MediaStore.Images.Media.DATE_MODIFIED)

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
                    put(MediaStore.Images.Media.DATE_MODIFIED, dateModified)
                }

                selectionArgs = arrayOf(destinationPath)
                activity.scanPath(destinationPath) {
                    activity.applicationContext.contentResolver.update(uri, values, selection, selectionArgs)
                }
            }
        }
    }
}
