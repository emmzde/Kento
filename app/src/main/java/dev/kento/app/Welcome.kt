package dev.kento.app

import android.content.Context
import android.content.Intent
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sin

private const val PREFS = "kento.ui"
private const val SEEN = "welcomed"
private const val GITHUB = "https://github.com/emmzde"

internal fun welcomed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false)
private fun markWelcomed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()

/**
 * The first screen after installing, in the spirit of an iPhone's "hello": OLED black, the Earth's limb at the bottom
 * with the sun coming up behind it, the lines arriving one after another out of a soft blur. "Continue" lets the light swell and the
 * screen dissolve into the chat; the welcome is not shown again.
 */
@Composable
internal fun Welcome(done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    val parts = remember { List(5) { Animatable(0f) } }
    val leave = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { rise.animateTo(1f, tween(2200, easing = EaseOutCubic)) }
        parts.forEachIndexed { i, part ->
            launch { delay(350L + i * 260L); part.animateTo(1f, tween(900, easing = EaseOutCubic)) }
        }
    }
    fun proceed() {
        if (leave.isRunning || leave.value > 0f) return
        markWelcomed(context)
        scope.launch { leave.animateTo(1f, tween(650, easing = EaseInCubic)); done() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).graphicsLayer {
        alpha = 1f - leave.value
        val zoom = 1f + .06f * leave.value
        scaleX = zoom; scaleY = zoom
    }) {
        // Read only while drawing: the intro, the dawn and "Continue" redraw the planet, they do not recompose the screen.
        Planet(Modifier.fillMaxSize(), { rise.value }, { leave.value })
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Image(painterResource(dev.kento.core.ui.R.drawable.kento_wordmark_mask), "Kento", Modifier.height(30.dp).reveal(parts[0]),
                colorFilter = ColorFilter.tint(Paper))
            Spacer(Modifier.height(34.dp))
            Text("Your best experience\nis here.", Modifier.reveal(parts[1]), color = Paper, fontSize = 40.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            Text("Made for people.\nFrom people.", Modifier.reveal(parts[2]), color = Muted, fontSize = 17.sp, lineHeight = 26.sp,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            // The people behind it: the author's GitHub, as a small glass chip under "From people."
            Row(Modifier.reveal(parts[3]).height(38.dp).glass(CircleShape)
                .clickable(role = Role.Button) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) } }
                .semantics { contentDescription = "GitHub emmzde" }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Glyph.Github, color = Paper, size = 17.dp)
                Text("emmzde", color = Paper.copy(alpha = .9f), fontSize = 14.5.sp)
            }
            Spacer(Modifier.weight(1.25f))
            Box(Modifier.reveal(parts[4]).padding(bottom = 26.dp).height(56.dp).widthIn(min = 220.dp).lightGlass(CircleShape)
                .clickable(role = Role.Button, onClick = ::proceed).padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                Text("Continue", color = Color(0xFF111110), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Fades in, rises a little and comes out of a blur as [part] goes from 0 to 1. The value is read inside the layer, so
 * the intro moves layers instead of recomposing the screen every frame; once in place nothing is blurred any more.
 */
private fun Modifier.reveal(part: Animatable<Float, AnimationVector1D>) = graphicsLayer {
    val p = part.value
    alpha = p
    translationY = (1f - p) * 26.dp.toPx()
    val radius = (1f - p) * 14.dp.toPx()
    // As Modifier.blur with its default rectangle edges, which needs the radius at composition time.
    renderEffect = if (radius > .5f) BlurEffect(radius, radius, TileMode.Clamp) else null
    if (radius > .5f) { shape = RectangleShape; clip = true }
}

/** Where the planet sits: its centre and radius in pixels. The limb rises a little with [rise] and again on [leave]. */
private class Orbit(val x: Float, val y: Float, val r: Float)

private fun DrawScope.orbit(rise: Float, leave: Float): Orbit {
    val r = size.width * 1.2f
    val top = size.height * (.77f - .04f * rise - .03f * leave)
    return Orbit(size.width / 2, top + r, r)
}

/**
 * The Earth seen from orbit at dawn: only the top of the globe shows, as a dark arc of ocean, land and clouds under a
 * thin lit atmosphere, and the sun comes up from behind the limb in a warm glow. The surface drifts slowly towards the
 * horizon, the glow breathes and a few faint stars twinkle, at about 25 frames a second; with animations switched off
 * in the system the planet stands still. [rise] brings the light up from nothing, [leave] swells it on "Continue";
 * all three are read while drawing.
 */
@Composable
private fun Planet(modifier: Modifier, rise: () -> Float, leave: () -> Float) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val time = remember { mutableFloatStateOf(0f) }
    if (!still) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { time.floatValue = (it - start) / 1e9f }
            delay(30)
        }
    }
    if (Build.VERSION.SDK_INT >= 33) ShaderPlanet(modifier, rise, leave) { time.floatValue }
    else DrawnPlanet(modifier, rise, leave) { time.floatValue }
}

@RequiresApi(33)
@Composable
private fun ShaderPlanet(modifier: Modifier, rise: () -> Float, leave: () -> Float, time: () -> Float) {
    val shader = remember { RuntimeShader(PLANET) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        val o = orbit(rise(), leave())
        shader.setFloatUniform("planet", o.x, o.y, o.r)
        shader.setFloatUniform("dp", 1.dp.toPx() / o.r)
        shader.setFloatUniform("time", time())
        shader.setFloatUniform("glow", rise() + leave() * .6f)
        drawRect(brush)
    }
}

/** Before Android 13 there are no runtime shaders: the same dawn from gradients, without the clouds and stars. */
@Composable
private fun DrawnPlanet(modifier: Modifier, riseOf: () -> Float, leaveOf: () -> Float, time: () -> Float) {
    Canvas(modifier) {
        val rise = riseOf()
        val leave = leaveOf()
        val o = orbit(rise, leave)
        val t = time()
        val g = (rise + leave * .6f) * (1f + .05f * sin(t * .45f) + .025f * sin(t * 1.13f + 2f))
        val dp = 1.dp.toPx()
        val center = Offset(o.x, o.y)
        val sun = Offset(o.x, o.y - o.r - (-4f + 7f * min(rise, 1f)) * dp)
        fun tint(r: Float, gr: Float, b: Float, alpha: Float) = Color(r, gr, b, (alpha * g).coerceIn(0f, 1f))
        scale(1f, .42f, pivot = sun) {
            drawCircle(Brush.radialGradient(0f to tint(1f, .55f, .2f, .75f), .3f to tint(1f, .42f, .12f, .32f), 1f to Color.Transparent,
                center = sun, radius = 300 * dp), 300 * dp, sun)
        }
        val air = o.r + 14 * dp
        drawCircle(Brush.radialGradient((o.r - 10 * dp) / air to Color.Transparent, o.r / air to tint(.38f, .6f, 1f, .55f),
            1f to Color.Transparent, center = center, radius = air), air, center)
        drawCircle(Brush.radialGradient(0f to tint(.42f, .22f, .1f, 1f), .12f to Color(.06f, .07f, .1f), 1f to Color(.01f, .012f, .02f),
            center = sun, radius = o.r * .9f), o.r, center)
        drawCircle(Brush.horizontalGradient(0f to Color.Transparent, .5f to tint(1f, .9f, .75f, .9f), 1f to Color.Transparent,
            startX = o.x - o.r * .45f, endX = o.x + o.r * .45f), o.r, center, style = Stroke(1.2f * dp))
        drawCircle(Brush.radialGradient(0f to tint(1f, .97f, .9f, 1f), .25f to tint(1f, .8f, .5f, .6f), 1f to Color.Transparent,
            center = sun, radius = 40 * dp), 40 * dp, sun)
    }
}

/**
 * AGSL for [ShaderPlanet]. `planet` is the centre and radius in pixels and `dp` one dp in planet radii, so the limb is
 * at r = 1. The surface is 3D value noise on the unit sphere (no seams), turned slowly about the screen's x axis so it
 * flows towards the horizon; the sun sits just behind the limb at the top and lights a thin crescent.
 */
private const val PLANET = """
uniform float3 planet;
uniform float dp;
uniform float time;
uniform float glow;

float hash2(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash3(float3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float3 x) {
    float3 i = floor(x);
    float3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i), hash3(i + float3(1.0, 0.0, 0.0)), f.x),
                   mix(hash3(i + float3(0.0, 1.0, 0.0)), hash3(i + float3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash3(i + float3(0.0, 0.0, 1.0)), hash3(i + float3(1.0, 0.0, 1.0)), f.x),
                   mix(hash3(i + float3(0.0, 1.0, 1.0)), hash3(i + float3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

float fbm3(float3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) { s += a * noise(p); p = p * 2.03 + float3(1.7, 9.2, 3.1); a *= 0.5; }
    return s / 0.875;
}

float fbm4(float3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) { s += a * noise(p); p = p * 2.03 + float3(1.7, 9.2, 3.1); a *= 0.5; }
    return s / 0.9375;
}

float fbm5(float3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) { s += a * noise(p); p = p * 2.03 + float3(1.7, 9.2, 3.1); a *= 0.5; }
    return s / 0.96875;
}

half4 main(float2 fc) {
    float R = planet.z;
    float px = dp * R;
    float2 q = (fc - planet.xy) / R;
    float r = length(q);
    float alt = (r - 1.0) / dp;
    float ang = atan(q.x, -q.y);
    float near = exp(-ang * ang * 9.0);
    float g = glow * (1.0 + 0.05 * sin(time * 0.45) + 0.025 * sin(time * 1.13 + 2.0));
    float sunAlt = -4.0 + 7.0 * min(glow, 1.0);
    float2 ds = float2(q.x, q.y + 1.0) / dp + float2(0.0, sunAlt);
    float sd = length(ds);
    float wide = length(ds * float2(0.55, 1.6));

    float above = max(alt, 0.0);
    float3 rimCol = mix(float3(0.32, 0.56, 1.0), float3(1.0, 0.78, 0.55), exp(-ang * ang * 60.0));
    float3 sky = rimCol * exp(-above / 3.0) * (0.22 + 1.5 * near) * g;
    sky += float3(0.12, 0.25, 0.62) * exp(-above / 16.0) * (0.12 + 0.45 * near) * g;
    float dawn = exp(-wide / 90.0) * 0.5 + exp(-wide / 30.0) * 0.9;
    sky += (float3(1.0, 0.36, 0.08) * exp(-wide / 90.0) * 0.5 + float3(1.0, 0.64, 0.28) * exp(-wide / 30.0) * 0.9) * g;

    float2 cell = floor(fc / (26.0 * px));
    float h = hash2(cell);
    if (h > 0.93 && alt > 0.0) {
        float2 o = float2(hash2(cell + 11.7), hash2(cell + 37.3));
        float d = length(fc - (cell + 0.15 + 0.7 * o) * 26.0 * px) / px;
        float b = (h - 0.93) / 0.07;
        float tw = 0.6 + 0.4 * sin(time * (0.4 + 1.2 * o.x) + 6.28 * o.y);
        sky += float3(0.8, 0.87, 1.0) * b * b * tw * exp(-d * d * 2.2) * smoothstep(0.0, 0.8, glow)
            * smoothstep(20.0, 80.0, alt) * (1.0 - smoothstep(0.02, 0.3, dawn * g));
    }

    float3 col = sky;
    float cover = clamp(0.5 - (r - 1.0) * R, 0.0, 1.0);
    if (cover > 0.0) {
        float z = sqrt(max(1.0 - r * r, 0.0));
        float3 n = float3(q, z);
        float a = -0.5 - time * 0.005;
        float ca = cos(a);
        float sa = sin(a);
        float3 pn = float3(n.x, n.y * ca - n.z * sa, n.y * sa + n.z * ca);
        float3 p = pn * 2.6;
        float land = smoothstep(0.5, 0.62, fbm4(p * 1.2 + float3(3.1, 7.4, 1.9)));
        float rough = noise(p * 6.0);
        float3 cp = pn * 6.0 + float3(time * 0.003, 0.0, 0.0);
        float warp = fbm3(cp * 1.6 + float3(5.2, 1.3, 8.8));
        float cloud = smoothstep(0.48, 0.78, fbm5(cp * 2.4 + warp * 1.8));
        float3 albedo = mix(float3(0.012, 0.028, 0.06), mix(float3(0.045, 0.04, 0.028), float3(0.09, 0.08, 0.055), rough), land);
        albedo = mix(albedo, float3(0.82, 0.85, 0.9), cloud);
        float3 L = normalize(float3(0.0, -0.45, -0.89));
        float ndl = dot(n, L);
        float day = smoothstep(-0.15, 0.4, ndl);
        float3 sunCol = mix(float3(1.0, 0.34, 0.1), float3(1.0, 0.85, 0.68), smoothstep(0.08, 0.42, ndl));
        float3 surf = albedo * sunCol * day * 2.2 * g;
        surf += albedo * float3(0.11, 0.14, 0.22) * 0.5 * min(glow, 1.0);
        float fres = pow(1.0 - z, 4.0);
        float3 haze = mix(float3(0.22, 0.42, 0.95), float3(1.0, 0.55, 0.25), near) * (0.2 + 0.9 * near) * g;
        surf = mix(surf, haze, fres * 0.8);
        surf += sky * exp(alt / 4.0);
        col = mix(sky, surf, cover);
    }

    col += float3(1.0, 0.95, 0.85) * exp(-sd / 5.5) * 6.0 * (1.0 - cover) * g;
    col += float3(1.0, 0.75, 0.45) * exp(-sd / 22.0) * 1.1 * g;
    col += float3(1.0, 0.6, 0.3) * exp(-abs(ds.y) / 1.5) * exp(-abs(ds.x) / 80.0) * 0.12 * g;
    col = 1.0 - exp(-col * 1.15);
    col += (hash2(fc) - 0.5) / 255.0;
    return half4(half3(col), 1.0);
}
"""

