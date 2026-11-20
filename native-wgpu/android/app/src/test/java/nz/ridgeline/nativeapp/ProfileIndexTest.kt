package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** The chart's O(log n) section and grade lookups match the reference
 * NativeGeo implementations exactly, including missing gaps. */
class ProfileIndexTest {
    private val p=Coordinate(170.1,-43.6)
    @Test fun sectionsAndGradesMatchTheReference(){
        val random=java.util.Random(7)
        repeat(20){round->
            var d=0.0
            val samples=List(300+round*37){NativeGeo.ProfilePoint(p,d.also{d+=0.5+random.nextDouble()*8},if(random.nextInt(40)==0)null else 1000+random.nextGaussian()*30)}
            val index=ProfileIndex(samples);val total=samples.last().distance
            repeat(400){
                val a=random.nextDouble()*total*1.1-total*.05;val b=if(it%10==0)samples[random.nextInt(samples.size)].distance else random.nextDouble()*total
                val expected=NativeGeo.section(samples,a,b);val actual=index.section(a,b)
                if(expected==null)assertNull(actual)else{actual!!
                    assertEquals(expected.start,actual.start,0.0);assertEquals(expected.end,actual.end,0.0);assertEquals(expected.complete,actual.complete)
                    assertEquals(expected.ascent,actual.ascent,1e-6);assertEquals(expected.descent,actual.descent,1e-6)}
                val g=random.nextDouble()*total
                val eg=NativeGeo.grade(samples,g);val ag=index.grade(g)
                if(eg==null)assertNull(ag)else assertEquals(eg,ag!!,1e-9)
            }
        }
    }
}
