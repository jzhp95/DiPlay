package com.shilapi.xcertplay.session

import android.view.Surface
import java.util.UUID

/** A page's output capability. The framework retains ownership of SurfaceHolder surfaces. */
internal data class ProjectionSurfaceBinding(val identity:SessionIdentity,val uiOwner:Int,val surface:Surface,
    val mirrorToAlt:Boolean,val bindingId:String=UUID.randomUUID().toString())
