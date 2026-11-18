package com.messenger.app.ui.call

import com.messenger.app.ui.call.CallMiniWindowPolicy as P
import kotlin.test.*

class CallMiniWindowPolicyTest {
    private val area = P.area(400f, 800f, 0f)
    private val medium = P.size(P.Step.MEDIUM, area)
    @Test fun `defaults match iOS geometry and four corners`() {
        assertEquals(P.Area(12f,112f,376f,588f), area)
        assertEquals(P.Size(120f,168f), medium)
        assertEquals(P.Point(268f,532f), P.origin(P.Placement(), medium, area,400f))
        for (corner in P.Corner.entries) {
            val p=P.origin(P.Placement(corner),medium,area,400f)
            assertEquals(if(corner.left) 12f else 268f,p.x)
            assertEquals(if(corner.top) 112f else 532f,p.y)
        }
    }
    @Test fun `sizes cap to sixty percent and free height but never below 64`() {
        assertEquals(P.Size(88f,123f),P.size(P.Step.SMALL,area))
        assertEquals(P.Size(168f,235f),P.size(P.Step.LARGE,area))
        assertEquals(64f,P.size(P.Step.LARGE,P.Area(0f,0f,80f,20f)).width)
        assertTrue(P.size(P.Step.LARGE,P.Area(0f,0f,200f,200f)).width<=120f)
    }
    @Test fun `keyboard gives up top then bottom clearance and bottom wins with no room`() {
        val landscape=P.area(700f,360f,150f)
        assertEquals(20f,landscape.y)
        assertEquals(90f,landscape.height)
        val short=P.area(700f,300f,170f)
        assertEquals(12f,short.y)
        assertEquals(90f,short.height)
        val impossible=P.area(400f,200f,160f)
        val size=P.size(P.Step.MEDIUM,impossible)
        val origin=P.origin(P.Placement(),size,impossible,400f)
        assertEquals(impossible.bottom,origin.y+size.height)
        assertTrue(origin.y<12f)
    }
    @Test fun `drag bounds allow tuck at three quarters and use same safe vertical range`() {
        assertEquals(P.Point(-90f,112f),P.dragged(P.Point(-1000f,-1000f),medium,area,400f))
        assertEquals(P.Point(370f,532f),P.dragged(P.Point(1000f,1000f),medium,area,400f))
    }
    @Test fun `release projects speed into quadrant or past fifth width tuck threshold`() {
        val p=P.Placement()
        assertEquals(P.Corner.TOP_LEFT,P.release(p,P.Point(80f,200f),P.Point(0f,0f),medium,area,400f).corner)
        assertEquals(P.Corner.BOTTOM_RIGHT,P.release(p,P.Point(80f,200f),P.Point(800f,1200f),medium,area,400f).corner)
        val tucked=P.release(p,P.Point(-90f,300f),P.Point(0f,0f),medium,area,400f)
        assertTrue(tucked.tucked); assertTrue(tucked.corner.left)
        assertFalse(P.release(p,P.Point(-80f,300f),P.Point(0f,0f),medium,area,400f).tucked)
        assertTrue(P.release(p,P.Point(100f,300f),P.Point(1400f,0f),medium,area,400f).tucked)
    }
    @Test fun `tuck origin tab and target keep fraction and hit sizes`() {
        val p=P.Placement(P.Corner.BOTTOM_LEFT,tucked=true,fraction=.5f)
        assertEquals(-136f,P.origin(p,medium,area,400f).x)
        assertEquals(-10f,P.tab(p,medium,area,400f).x)
        assertEquals(30f,P.tab(p,medium,area,400f).width)
        assertEquals(64f,P.tab(p,medium,area,400f).height)
        val target=P.tabTarget(P.tab(p,medium,area,400f))
        assertEquals(44f,target.width);assertEquals(88f,target.height)
        assertEquals(P.Corner.TOP_LEFT,P.untuck(p,.2f).corner)
        assertFalse(P.untuck(p).tucked)
        assertEquals(0f,P.fraction(-100f,medium,area));assertEquals(1f,P.fraction(2000f,medium,area))
    }
    @Test fun `velocity uses last hundred milliseconds and becomes zero after pause`() {
        val samples=listOf(P.Sample(P.Point(0f,0f),0),P.Sample(P.Point(20f,10f),100),P.Sample(P.Point(30f,10f),150))
        assertEquals(P.Point(200f,0f),P.velocity(samples))
        assertEquals(P.Point(0f,0f),P.velocity(samples+P.Sample(P.Point(30f,10f),300)))
        assertEquals(12f,P.springVelocity(P.Point(10000f,0f),P.Point(0f,0f),P.Point(10f,0f)))
        assertEquals(0f,P.springVelocity(P.Point(-100f,0f),P.Point(0f,0f),P.Point(10f,0f)))
    }
    @Test fun `pinch suppresses dragging for whole sequence including remaining finger`() {
        val gesture=CallMiniGesturePolicy();gesture.begin();assertTrue(gesture.acceptsDrag())
        gesture.observe(2);assertFalse(gesture.acceptsDrag());gesture.observe(1);assertFalse(gesture.acceptsDrag())
        gesture.observe(0);assertFalse(gesture.acceptsDrag());gesture.begin();assertTrue(gesture.acceptsDrag())
        assertEquals(P.Step.SMALL,P.nearest(90f));assertEquals(P.Step.LARGE,P.nearest(165f))
        assertEquals(P.Step.MEDIUM,P.next(P.Step.SMALL));assertEquals(P.Step.SMALL,P.next(P.Step.LARGE))
    }
    @Test fun `hide only window and next minimize reveals with same per call placement`() {
        val memory=CallMiniWindowMemory();val p=P.Placement(P.Corner.TOP_LEFT,P.Step.LARGE,true,.2f)
        memory.store("a",p);memory.hide("a");assertTrue(memory.hidden);assertEquals(p,memory.placement("a"))
        memory.minimized("a");assertFalse(memory.hidden);assertEquals(p,memory.placement("a"))
        assertEquals(P.Placement(),memory.placement("b"));assertFalse(memory.hidden)
    }
    @Test fun `RTL default is physical left and existing placement survives direction recreation`() {
        val memory=CallMiniWindowMemory()
        memory.minimized("a")
        assertEquals(P.Corner.BOTTOM_LEFT,memory.placement("a",P.Corner.BOTTOM_LEFT).corner)
        val stored=P.Placement(P.Corner.TOP_RIGHT,P.Step.LARGE,true,.2f)
        memory.store("a",stored)
        assertEquals(stored,memory.placement("a",P.Corner.BOTTOM_LEFT))
        memory.clear()
        memory.minimized("b")
        assertEquals(P.Placement(P.Corner.BOTTOM_LEFT),memory.placement("b",P.Corner.BOTTOM_LEFT))
    }
    @Test fun `drawer exclusion includes cross margin or exact tab target`() {
        assertTrue(P.drawerExcluded(P.Point(5f,105f),P.Area(12f,112f,120f,168f),false))
        assertFalse(P.drawerExcluded(P.Point(3f,105f),P.Area(12f,112f,120f,168f),false))
        val target=P.tabTarget(P.tab(P.Placement(tucked=true),medium,area,400f))
        assertTrue(P.drawerExcluded(P.Point(target.x,target.y),target,true))
        assertFalse(P.drawerExcluded(P.Point(target.x-1,target.y),target,true))
    }
}
