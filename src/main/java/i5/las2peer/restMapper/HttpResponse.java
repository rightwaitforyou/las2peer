package i5.las2peer.restMapper;

import i5.las2peer.restMapper.data.MethodData;
import i5.las2peer.restMapper.data.Pair;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Alexander
 */
public class HttpResponse implements Serializable
{

    private static final long serialVersionUID = 1618302269572403581L;

    private int status=200;

    public String getResult()
    {
        return result;
    }

    private String result;
    private HashMap<String,String> headers= new HashMap<String,String> ();

    public HttpResponse(String result)
    {
        this.result=result;
    }

    public HttpResponse(String result,int status)
    {
        this.status = status;
        this.result = result;
    }
    public void setStatus(int status)
    {
        this.status = status;
    }
    public int getStatus()
    {
        return status;
    }
    public void setHeader(String header, String value)
    {
        headers.put(header,value);
    }
    public void removeHeader(String header)
    {
        headers.remove(header);
    }
    public Pair[] listHeaders()
    {
        Pair[] result = new Pair[headers.size()];
        int i=0;
        for (Map.Entry d : headers.entrySet()) {

            result[i]= new Pair<String>((String)d.getKey(),(String)d.getValue());
            i++;
        }
        return result;
    }
}
