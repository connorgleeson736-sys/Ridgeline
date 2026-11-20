package nz.ridgeline.nativeapp

import kotlin.math.*

/** World Magnetic Model (NOAA/NCEI and BGS; public domain): declination at
 * a place and date, from the model's coefficient file (assets/wmm). The
 * standard spherical-harmonic synthesis, as NOAA's reference software. */
internal class MagneticModel(cof:String) {
    private val maxOrder=12
    val epoch:Double
    // c[m][n] holds g and c[n][m-1] holds h (Schmidt semi-normalised), as the reference code.
    private val c=Array(13){DoubleArray(13)};private val cd=Array(13){DoubleArray(13)}
    private val k=Array(13){DoubleArray(13)}
    private val fn=DoubleArray(13);private val fm=DoubleArray(13)
    init{
        val lines=cof.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList()
        epoch=lines.first().split(Regex("\\s+"))[0].toDouble()
        for(line in lines.drop(1)){
            if(line.startsWith("9999"))break
            val v=line.split(Regex("\\s+"));val n=v[0].toInt();val m=v[1].toInt()
            if(n>maxOrder||m>n)continue
            c[m][n]=v[2].toDouble();cd[m][n]=v[4].toDouble()
            if(m!=0){c[n][m-1]=v[3].toDouble();cd[n][m-1]=v[5].toDouble()}
        }
        // Schmidt normalisation.
        val snorm=Array(13){DoubleArray(13)};snorm[0][0]=1.0
        for(n in 1..maxOrder){
            snorm[0][n]=snorm[0][n-1]*(2.0*n-1)/n
            var j=2.0
            for(m in 0..n){
                k[m][n]=((n-1.0)*(n-1)-m*m)/((2.0*n-1)*(2.0*n-3))
                if(m>0){
                    val flnmj=((n-m+1.0)*j)/(n+m)
                    snorm[m][n]=snorm[m-1][n]*sqrt(flnmj);j=1.0
                    c[n][m-1]*=snorm[m][n];cd[n][m-1]*=snorm[m][n]
                }
                c[m][n]*=snorm[m][n];cd[m][n]*=snorm[m][n]
            }
            fn[n]=n+1.0;fm[n]=n.toDouble()
        }
        k[1][1]=0.0;fn[0]=1.0;fm[0]=0.0
    }
    /** Declination in degrees (east positive) at geodetic [latitude], [longitude]
     * (degrees), [altitudeKm] above the ellipsoid, on [year] (decimal). */
    fun declination(latitude:Double,longitude:Double,year:Double,altitudeKm:Double=0.0):Double{
        val a=6378.137;val b=6356.7523142;val re=6371.2
        val a2=a*a;val b2=b*b;val c2=a2-b2;val a4=a2*a2;val b4=b2*b2;val c4=a4-b4
        val dt=year-epoch
        val rlon=Math.toRadians(longitude);val rlat=Math.toRadians(latitude)
        val srlon=sin(rlon);val crlon=cos(rlon);val srlat=sin(rlat);val crlat=cos(rlat)
        val srlat2=srlat*srlat;val crlat2=crlat*crlat
        // Geodetic to spherical coordinates.
        val q=sqrt(a2-c2*srlat2);val q1=altitudeKm*q;val q2=((q1+a2)/(q1+b2)).pow(2)
        val ct=srlat/sqrt(q2*crlat2+srlat2);val st=sqrt(1-ct*ct)
        val r2=altitudeKm*altitudeKm+2*q1+(a4-c4*srlat2)/(q*q);val r=sqrt(r2)
        val d=sqrt(a2*crlat2+b2*srlat2)
        val ca=(altitudeKm+d)/r;val sa=c2*crlat*srlat/(r*d)
        val sp=DoubleArray(13);val cp=DoubleArray(13);sp[0]=0.0;cp[0]=1.0;sp[1]=srlon;cp[1]=crlon
        for(m in 2..maxOrder){sp[m]=sp[1]*cp[m-1]+cp[1]*sp[m-1];cp[m]=cp[1]*cp[m-1]-sp[1]*sp[m-1]}
        val p=Array(13){DoubleArray(13)};val dp=Array(13){DoubleArray(13)};p[0][0]=1.0;dp[0][0]=0.0
        val aor=re/r;var ar=aor*aor
        var br=0.0;var bt=0.0;var bp=0.0
        for(n in 1..maxOrder){
            ar*=aor
            for(m in 0..n){
                if(n==m){p[m][n]=st*p[m-1][n-1];dp[m][n]=st*dp[m-1][n-1]+ct*p[m-1][n-1]}
                else if(n==1&&m==0){p[m][n]=ct*p[m][n-1];dp[m][n]=ct*dp[m][n-1]-st*p[m][n-1]}
                else if(n>1&&n!=m){
                    if(m>n-2){p[m][n-2]=0.0;dp[m][n-2]=0.0}
                    p[m][n]=ct*p[m][n-1]-k[m][n]*p[m][n-2]
                    dp[m][n]=ct*dp[m][n-1]-st*p[m][n-1]-k[m][n]*dp[m][n-2]
                }
                val g=c[m][n]+dt*cd[m][n];val h=if(m!=0)c[n][m-1]+dt*cd[n][m-1]else 0.0
                val par=ar*p[m][n]
                val temp1:Double;val temp2:Double
                if(m==0){temp1=g*cp[m];temp2=g*sp[m]}else{temp1=g*cp[m]+h*sp[m];temp2=g*sp[m]-h*cp[m]}
                bt-=ar*temp1*dp[m][n]
                bp+=fm[m]*temp2*par
                br+=fn[n]*temp1*par
            }
        }
        if(st!=0.0)bp/=st
        val bx=-bt*ca-br*sa;val by=bp
        return Math.toDegrees(atan2(by,bx))
    }
    companion object{
        /** Decimal year for [millis] (UTC). */
        fun year(millis:Long):Double{
            val cal=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply{timeInMillis=millis}
            val y=cal.get(java.util.Calendar.YEAR);val days=cal.getActualMaximum(java.util.Calendar.DAY_OF_YEAR)
            return y+(cal.get(java.util.Calendar.DAY_OF_YEAR)-1+cal.get(java.util.Calendar.HOUR_OF_DAY)/24.0)/days
        }
    }
}
