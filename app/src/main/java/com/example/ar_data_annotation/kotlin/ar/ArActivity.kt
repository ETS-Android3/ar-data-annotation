/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.ar_data_annotation.kotlin.ar

import android.graphics.Color
import android.graphics.Typeface.BOLD
import android.os.Bundle
import android.text.Editable
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ar_data_annotation.R
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.example.ar_data_annotation.java.common.helpers.CameraPermissionHelper
import com.example.ar_data_annotation.java.common.helpers.DepthSettings
import com.example.ar_data_annotation.java.common.helpers.FullScreenHelper
import com.example.ar_data_annotation.java.common.helpers.InstantPlacementSettings
import com.example.ar_data_annotation.java.common.samplerender.SampleRender
import com.example.ar_data_annotation.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.Anchor
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class ArActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "ArActivity"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: ArView
  lateinit var renderer: ArRenderer

  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()

  lateinit var searchView: SearchView
  lateinit var listView: ListView
  lateinit var adapter: ArrayAdapter<*>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features, including: Lighting Estimation, Depth mode, Instant Placement.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = ArRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = ArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    // Sets up an example renderer using our HelloARRenderer.
    SampleRender(view.surfaceView, renderer, assets)
    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)

    // search bar initialisation
    searchView = findViewById(R.id.searchView)
    listView = findViewById(R.id.listView)

    adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, renderer.getSearchList1())
    listView.adapter = adapter
    listView.setVisibility(View.GONE);
    renderer.setSearchListener(adapter as ArrayAdapter<String>,searchView,listView)
  }

  fun setNewTextView(textString: String, x: Float, y: Float, anchorId: Int): TextView {
    var newTextView = TextView(applicationContext)
    runOnUiThread {
//      Log.v(TAG, "AddingText 1")
      val rel: RelativeLayout = findViewById(R.id.mainrelativelayout);

      renderer.setAnchorText(textString, newTextView, anchorId)
      newTextView.setLayoutParams(
        RelativeLayout.LayoutParams(
          RelativeLayout.LayoutParams.WRAP_CONTENT,
          RelativeLayout.LayoutParams.WRAP_CONTENT
        )
      )
      newTextView.setTextColor(Color.RED)
      newTextView.setTypeface(null, BOLD)
      newTextView.x = x
      newTextView.y = y

      newTextView.setShadowLayer(10f, 10f, 10f, Color.BLACK)
      rel.addView(newTextView)
//      Log.v(TAG, "AddingText added text " + rel.id)
    }
    return newTextView
  }

  fun promptAnchorText(wrappedAnchor: WrappedAnchor): String {
    var anchorStr = "New Marker"
    var input : EditText? = null
    runOnUiThread {
      val alert: AlertDialog.Builder = AlertDialog.Builder(this)

      alert.setTitle("Title")
      alert.setMessage("Message")

      input = EditText(this)
      alert.setView(input)

      alert.setPositiveButton("Submit") { _, _ ->
        val value: Editable? = input!!.text
        anchorStr = value.toString();
        if(anchorStr.length > 0) {
          renderer.setAnchorText(anchorStr, wrappedAnchor.anchorText, wrappedAnchor.anchor.hashCode())
          Log.v(TAG, "Assigning text")
        }
      }
      alert.show()
    }
    return anchorStr;
  }

  fun populateDisplayMetrics(displayMetrics : DisplayMetrics) {
    getWindowManager().getDefaultDisplay().getMetrics(displayMetrics)
  }

  // Configure the session, using Lighting Estimation, and Depth mode.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Depth API is used if it is configured in Hello AR's settings.
        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        // Instant Placement is used if it is configured in Hello AR's settings.
        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            InstantPlacementMode.LOCAL_Y_UP
          } else {
            InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
