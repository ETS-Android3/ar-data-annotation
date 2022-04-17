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

import android.opengl.GLES30
import android.opengl.Matrix
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ar_data_annotation.R
import com.example.ar_data_annotation.java.common.helpers.DisplayRotationHelper
import com.example.ar_data_annotation.java.common.helpers.TrackingStateHelper
import com.example.ar_data_annotation.java.common.samplerender.*
import com.example.ar_data_annotation.java.common.samplerender.arcore.BackgroundRenderer
import com.example.ar_data_annotation.java.common.samplerender.arcore.PlaneRenderer
import com.example.ar_data_annotation.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.IOException
import java.nio.ByteBuffer


/** Renders the HelloAR application using our example Renderer. */
class ArRenderer(val activity: ArActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    val TAG = "ArRenderer"
    var anchorCounter = 1
    var defaultAnchorText = "New Marker"

    // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
    // constants.
    private val sphericalHarmonicFactors =
      floatArrayOf(
        0.282095f,
        -0.325735f,
        0.325735f,
        -0.325735f,
        0.273137f,
        -0.273137f,
        0.078848f,
        -0.273137f,
        0.136569f
      )

    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f

    private val MIN_TEXT_SIZE = 5
    private val MAX_TEXT_SIZE = 40
    // Assumed distance from the device camera to the surface on which user will try to place
    // objects.
    // This value affects the apparent scale of objects while the tracking method of the
    // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
    // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
    // values for AR experiences where users are expected to place objects on surfaces close to the
    // camera. Use larger values for experiences where the user will likely be standing and trying
    // to
    // place an object on the ground or floor in front of them.
    val APPROXIMATE_DISTANCE_METERS = 2.0f

    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
  }
  // Map of Instant Placement Object id and user defined keyword
  // For Id (hashCode), store newAnchor.hashCode() instead of firstHitResult.hashCode()
  val keywordToId = HashMap<String, Int>()
  val IdToKeyword = HashMap<Int, String>()
  val searchResult = HashSet<Int>()

  lateinit var render: SampleRender
  lateinit var planeRenderer: PlaneRenderer
  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Point Cloud
  lateinit var pointCloudVertexBuffer: VertexBuffer
  lateinit var pointCloudMesh: Mesh
  lateinit var pointCloudShader: Shader

  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  var lastPointCloudTimestamp: Long = 0

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectAlbedoTexture: Texture
  lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  private val wrappedAnchors = mutableListOf<WrappedAnchor>()

  // Environmental HDR
  lateinit var dfgTexture: Texture
  lateinit var cubemapFilter: SpecularCubemapFilter

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  val viewInverseMatrix = FloatArray(16)
  val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
  val viewLightDirection = FloatArray(4) // view x world light direction
  @Volatile var searchList: ArrayList<String> = ArrayList()
  @Volatile var matchingSet: ArrayList<String> = ArrayList()
  var rendAdapter: ArrayAdapter<String>? = null
  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  fun getSearchList1(): ArrayList<String> {
    /*searchList.add("Apple")
    searchList.add("Banana")
    searchList.add("Pineapple")
    searchList.add("Orange")
    searchList.add("Mango")
    searchList.add("Grapes")*/
//    searchList.add("Lemon")
//    searchList.add("Melon")
//    searchList.add("Watermelon")
//    searchList.add("Papaya")
    // keep 0 markers initially
    return searchList
  }

  fun addItemtoAdapter(item: String, anchorId: Int) {
    rendAdapter?.add(item) // to modify search list use this function and notify changes to adapter
    rendAdapter?.notifyDataSetChanged()
  }

  fun removeItemFromAdapter(item: String, anchorId: Int) {
    if(rendAdapter!=null && rendAdapter!!.getPosition(item) >= 0)
      rendAdapter?.remove(item) // to modify search list use this function and notify changes to adapter
    rendAdapter?.notifyDataSetChanged()

  }

  fun getMatchedId(): ArrayList<Int> {
    var matchedIds: ArrayList<Int> = ArrayList()
    for(matchedItem in matchingSet)
    {
      matchedIds.add(keywordToId.getOrDefault(matchedItem,-1))
    }
    return matchedIds
  }


  //  // This method implements the search logic
  fun setSearchListener(adapter: ArrayAdapter<String>, searchView: SearchView, listView: ListView) {
    rendAdapter = adapter
    searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
      override fun onQueryTextSubmit(query: String): Boolean {

        if (searchList.contains(query)) {
          adapter.filter.filter(query)

          for (i in 0..(adapter.count-1)) {
            matchingSet.add(adapter.getItem(i).toString())
          }

        } else {
          Toast.makeText(activity, "No Match found", Toast.LENGTH_LONG).show()
        }
        return true
      }
      override fun onQueryTextChange(newText: String): Boolean {
        adapter.filter.filter(newText)
//        Log.e(TAG, "Sam $i")
        if(TextUtils.isEmpty(newText)){
          listView.setVisibility(View.GONE);
        }
        else {
        listView.setVisibility(View.VISIBLE);
        }
        return true;
      }
    })
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      cubemapFilter =
        SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      // Load environmental lighting values lookup table
      dfgTexture =
        Texture(
          render,
          Texture.Target.TEXTURE_2D,
          Texture.WrapMode.CLAMP_TO_EDGE,
          /*useMipmaps=*/ false
        )
      // The dfg.raw file is a raw half-float texture with two channels.
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2

      val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        /*level=*/ 0,
        GLES30.GL_RG16F,
        /*width=*/ dfgResolution,
        /*height=*/ dfgResolution,
        /*border=*/ 0,
        GLES30.GL_RG,
        GLES30.GL_HALF_FLOAT,
        buffer
      )
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      // Point cloud
      pointCloudShader =
        Shader.createFromAssets(
            render,
            "shaders/point_cloud.vert",
            "shaders/point_cloud.frag",
            /*defines=*/ null
          )
          .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
          .setFloat("u_PointSize", 5.0f)

      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
        VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
      val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
      pointCloudMesh =
        Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectAlbedoInstantPlacementTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo_instant_placement.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      val virtualObjectPbrTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_roughness_metallic_ao.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.LINEAR
        )
      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
      virtualObjectShader =
        Shader.createFromAssets(
            render,
            "shaders/environmental_hdr.vert",
            "shaders/environmental_hdr.frag",
            mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
          )
          .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
          .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
          .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
          .setTexture("u_DfgTexture", dfgTexture)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
        render,
        activity.depthSettings.depthColorVisualizationEnabled()
      )
      backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
      return
    }

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)
    val shouldGetDepthImage =
      activity.depthSettings.useDepthForOcclusion() ||
        activity.depthSettings.depthColorVisualizationEnabled()
    if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
      try {
        val depthImage = frame.acquireDepthImage()
        backgroundRenderer.updateCameraDepthTexture(depthImage)
        depthImage.close()
      } catch (e: NotYetAvailableException) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Handle one tap per frame.
    handleTap(frame, camera)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    val message: String? =
      when {
        camera.trackingState == TrackingState.PAUSED &&
          camera.trackingFailureReason == TrackingFailureReason.NONE ->
          activity.getString(R.string.searching_planes)
        camera.trackingState == TrackingState.PAUSED ->
          TrackingStateHelper.getTrackingFailureReasonString(camera)
        session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
          activity.getString(R.string.waiting_taps)
        session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
        else -> activity.getString(R.string.searching_planes)
      }
    if (message == null) {
      activity.view.snackbarHelper.hide(activity)
    } else {
      activity.view.snackbarHelper.showMessage(activity, message)
    }

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)
    frame.acquirePointCloud().use { pointCloud ->
      if (pointCloud.timestamp > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.points)
        lastPointCloudTimestamp = pointCloud.timestamp
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(pointCloudMesh, pointCloudShader)
    }

    // Visualize planes.
    planeRenderer.drawPlanes(
      render,
      session.getAllTrackables<Plane>(Plane::class.java),
      camera.displayOrientedPose,
      projectionMatrix
    )

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.lightEstimate, viewMatrix)

    // Visualize anchors created by touch.
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

    // If search, filter with ids by calling show_anchor_from_search
    if (matchingSet.isNotEmpty()) {
      // for test
      // keywordToId.put("Lemon", 123);

      Log.v(TAG, "matchingSet not empty, matchingSet=" + matchingSet);
      val matchedIds = getMatchedId()
      Log.v(TAG, "matchedIds=" + matchedIds);
      val searched_anchors = show_anchor_from_search(matchedIds)
      Log.v(TAG, "searched_anchors=" + searched_anchors);
      val not_searched_anchors = show_anchor_not_from_search(matchedIds)

      Log.v(TAG, "not_searched_anchors=" + not_searched_anchors);

      // draw searched anchors
      for ((anchor, trackable, anchorText) in searched_anchors) {
        Log.v(TAG, "anchor.pose=" + anchor.pose);
        anchor.pose.toMatrix(modelMatrix, 0)

        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        val texture =
          if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
            InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
          ) {
            virtualObjectAlbedoInstantPlacementTexture
          } else {
            virtualObjectAlbedoTexture
          }
        virtualObjectShader.setTexture("u_AlbedoTexture", texture)
        render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
      }

      // draw not searched anchors
      for ((anchor, trackable, anchorText) in not_searched_anchors) {
        Log.v(TAG, "anchor.pose=" + anchor.pose);
        updateAnchorText(anchor, anchorText, camera, modelMatrix, viewMatrix, projectionMatrix, 1)
      }

    }
    // If no search
    else {
      for ((anchor, trackable, anchorText) in
        wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
        // Get the current pose of an Anchor in world space. The Anchor pose is updated
        // during calls to session.update() as ARCore refines its estimate of the world.
        anchor.pose.toMatrix(modelMatrix, 0)
        updateAnchorText(anchor, anchorText, camera, modelMatrix, viewMatrix, projectionMatrix, 0)

        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        val texture =
          if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
            InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
          ) {
            virtualObjectAlbedoInstantPlacementTexture
          } else {
            virtualObjectAlbedoTexture
          }
        virtualObjectShader.setTexture("u_AlbedoTexture", texture)
        render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
      }
    }



    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  private fun updateAnchorText(
    anchor: Anchor,
    anchorText: TextView,
    camera: Camera,
    modelMatrix: FloatArray,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    not_searched: Int
  ) {
    val displayMetrics = DisplayMetrics()
    activity.populateDisplayMetrics(displayMetrics)
    val anchor_2d = get2DAnchorCoordinates(
      anchor,
      camera,
      displayMetrics,
      modelMatrix,
      viewMatrix,
      projectionMatrix
    )

    var anchorToCamDist = getDistFromCamera(anchor, camera)
    var textSize = getTextSize(anchorToCamDist, not_searched)
    activity.runOnUiThread{ anchorText.setTextSize(textSize) }

    anchorText.x = anchor_2d[0] - (anchorText.width/2)
    anchorText.y = anchor_2d[1]
  }

  private fun getTextSize(anchorDist : Double, not_searched : Int) : Float {
    if (not_searched == 1) {
      return 0.0f
    }
    else {
      val maxReducableDist = 2.0 //You can have anchors farther than that, they just wont have their text size shrunk further
      var dist = anchorDist
      if(anchorDist>maxReducableDist)
        dist = maxReducableDist
      var sizeScale = maxReducableDist - dist
      return (MIN_TEXT_SIZE + ((sizeScale / maxReducableDist) * (MAX_TEXT_SIZE - MIN_TEXT_SIZE))).toFloat()
    }
  }

  private fun getDistFromCamera(anchor: Anchor, camera : Camera): Double {
    return dictCalculation3D(anchor.pose.tx(), camera.pose.tx(), anchor.pose.ty(), camera.pose.ty(), anchor.pose.tz(), camera.pose.tz())
  }

  private fun dictCalculation3D(x1 : Float, x2 : Float, y1 : Float, y2: Float, z1 : Float, z2 : Float) : Double {
    return Math.sqrt(Math.pow((x1-x2).toDouble(), 2.0) + Math.pow((y1-y2).toDouble(), 2.0) + Math.pow(
      (z1-z2).toDouble(), 2.0))
  }


  /** Checks if we detected at least one plane. */
  private fun Session.hasTrackingPlane() =
    getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

  /** Update state based on the current frame's light estimation. */
  private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
    if (lightEstimate.state != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false)
      return
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true)
    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
    updateMainLight(
      lightEstimate.environmentalHdrMainLightDirection,
      lightEstimate.environmentalHdrMainLightIntensity,
      viewMatrix
    )
    updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
  }

  private fun updateMainLight(
    direction: FloatArray,
    intensity: FloatArray,
    viewMatrix: FloatArray
  ) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
    require(coefficients.size == 9 * 3) {
      "The given coefficients array must be of length 27 (3 components per 9 coefficients"
    }

    // Apply each factor to every component of each coefficient
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array(
      "u_SphericalHarmonicsCoefficients",
      sphericalHarmonicsCoefficients
    )
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private fun handleTap(frame: Frame, camera: Camera) {
    if (camera.trackingState != TrackingState.TRACKING) return
    val tap = activity.view.tapHelper.poll() ?: return

    val hitResultList =
      if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
        frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
      } else {
        frame.hitTest(tap)
      }

    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, Depth Point,
    // or Instant Placement Point.
    val firstHitResult =
      hitResultList.firstOrNull { hit ->
        when (val trackable = hit.trackable!!) {
          is Plane ->
            trackable.isPoseInPolygon(hit.hitPose) &&
              PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
          is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
          is InstantPlacementPoint -> true
          // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
          is DepthPoint -> true
          else -> false
        }
      }

    if (firstHitResult != null) {
      // https://developers.google.com/ar/reference/java/com/google/ar/core/HitResult
      Log.v(TAG, "create object distance=" + firstHitResult.getDistance());
      Log.v(TAG, "create object pose=" + firstHitResult.getHitPose());
      Log.v(TAG, "create object trackable=" + firstHitResult.getTrackable());
      Log.v(TAG, "create object hashCode=" + firstHitResult.hashCode());
      // Cap the number of objects created. This avoids overloading both the
      // rendering system and ARCore.
      if (wrappedAnchors.size >= 20) {
        wrappedAnchors[0].anchor.detach()
        wrappedAnchors.removeAt(0)
      }

      // Adding an Anchor tells ARCore that it should track this position in
      // space. This anchor is created on the Plane to place the 3D model
      // in the correct position relative both to the world and to the plane.
      val newAnchor = firstHitResult.createAnchor();
      Log.v(TAG, "create object newAnchor.hashCode=" + newAnchor.hashCode());
      var newWrappedAnchor = WrappedAnchor(newAnchor, firstHitResult.trackable, activity.setNewTextView(defaultAnchorText + " #${anchorCounter}", 0f, 0f, newAnchor.hashCode()))
      activity.promptAnchorText(newWrappedAnchor)
      anchorCounter++

      wrappedAnchors.add(newWrappedAnchor)

      Log.v(TAG, "create object wrappedAnchors[0].anchor.hashCode=" + wrappedAnchors[0].anchor.hashCode());
      // For devices that support the Depth API, shows a dialog to suggest enabling
      // depth-based occlusion. This dialog needs to be spawned on the UI thread.
      activity.runOnUiThread { activity.view.showOcclusionDialogIfNeeded() }
    }
  }

  private fun get2DAnchorCoordinates(
    anchor: Anchor,
    camera: Camera,
    displayMetrics: DisplayMetrics,
    modelMatrix: FloatArray,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray
  ): FloatArray {
    val spatial3Dto2Dmatrix: FloatArray =
      calculate3Dto2DMatrix(modelMatrix, viewMatrix, projectionMatrix)
    return calculate3Dto2D(displayMetrics.widthPixels, displayMetrics.heightPixels, spatial3Dto2Dmatrix)
  }

  fun setAnchorText(newAnchorText: String, textView: TextView, anchorId: Int) {
    val curText:String = textView.text.toString()
    if(curText != null && curText.length >= 0) {
      removeItemFromAdapter(curText, anchorId);
      removePointAnnotation(anchorId, newAnchorText)
    }
    addItemtoAdapter(newAnchorText, anchorId)
    addPointAnnotation(anchorId, newAnchorText)

    textView.setText(newAnchorText)
  }

  private fun calc2DCoordinates(
    screenWidth: Int,
    resultVec: FloatArray,
    screenHeight: Int
  ): FloatArray {
    val positionCoordinates = floatArrayOf(0.0f, 0.0f)
    positionCoordinates[0] = screenWidth * ((resultVec[0] + 1.0f) / 2.0f)
    positionCoordinates[1] = screenHeight * ((1.0f - resultVec[1]) / 2.0f)
    return positionCoordinates
  }

  private fun addPointAnnotation(hashCode: Int, keyword: String) {
    IdToKeyword.put(hashCode, keyword)
    keywordToId.put(keyword, hashCode)
  }

  private fun removePointAnnotation(hashCode: Int, keyword: String) {
    IdToKeyword.remove(hashCode, keyword)
    keywordToId.remove(keyword, hashCode)
  }

  private fun show_anchor_from_search(matchedIds: ArrayList<Int>): List<WrappedAnchor> {
    val searched_wrappedAnchors = wrappedAnchors.filter { (anchor, trackable) -> matchedIds.contains(anchor.hashCode()) }

    return searched_wrappedAnchors;
  }

  private fun show_anchor_not_from_search(matchedIds: ArrayList<Int>): List<WrappedAnchor> {
    val not_searched_wrappedAnchors = wrappedAnchors.filter { (anchor, trackable) -> !matchedIds.contains(anchor.hashCode()) }

    return not_searched_wrappedAnchors;
  }

  private fun ids_to_keywords(ids: HashSet<Int>): List<String> {
    val filtered_IdToKeyword = IdToKeyword.filter { (id, keyword) -> ids.contains(id) }
    val keywords = mutableListOf<String>()

    for (e in filtered_IdToKeyword) {
      keywords.add(e.value);
    }

    return keywords;
  }

  fun calculate3Dto2DMatrix(
    modelMatrix: FloatArray?,
    viewMatrix: FloatArray?,
    projectMatrix: FloatArray?
  ): FloatArray {
    val scaling = 1.0f
    val scalingMatrix = FloatArray(16)
    scalingMatrix[0] = scaling
    scalingMatrix[5] = scaling
    scalingMatrix[10] = scaling

    Matrix.setIdentityM(scalingMatrix, 0)

    val modelScaleProductMatrix = FloatArray(16)
    Matrix.multiplyMM(modelScaleProductMatrix, 0, modelMatrix, 0, scalingMatrix, 0)

    val viewModelScaleProductMatrix = FloatArray(16)
    Matrix.multiplyMM(viewModelScaleProductMatrix, 0, viewMatrix, 0, modelScaleProductMatrix, 0)

    val scaling3Dto2DMatrix = FloatArray(16)
    Matrix.multiplyMM(scaling3Dto2DMatrix, 0, projectMatrix, 0, viewModelScaleProductMatrix, 0)
    return scaling3Dto2DMatrix
  }

  fun calculate3Dto2D(
    screenWidth: Int,
    screenHeight: Int,
    spatial3Dto2DMatrix: FloatArray
  ): FloatArray {
    val origCoord = floatArrayOf(0f, 0f, 0f, 1f)
    val resultVec = FloatArray(4)
    Matrix.multiplyMV(resultVec, 0, spatial3Dto2DMatrix, 0, origCoord, 0)
    resultVec[0] /= resultVec[3]
    resultVec[1] /= resultVec[3]

    return calc2DCoordinates(screenWidth, resultVec, screenHeight)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
data class WrappedAnchor(
  val anchor: Anchor,
  val trackable: Trackable,
  val anchorText: TextView
)
