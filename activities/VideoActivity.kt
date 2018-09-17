package app.pr0.com.aquariumcamera.Activities

import android.os.Bundle
import app.pr0.com.aquariumcamera.Activities.PhotoVideoActivity

class VideoActivity : PhotoVideoActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        mIsVideo = true
        super.onCreate(savedInstanceState)
    }
}
