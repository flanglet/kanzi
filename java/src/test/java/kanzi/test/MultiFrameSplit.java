package kanzi.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;


public class MultiFrameSplit
{
   public static void main(String[] args) throws Exception
   {
      String fileName = (args.length > 1) ? args[1] : "e:\\temp\\gdc21\\t2_demo";//r:\\tele0_432x256x8x16s";
      String[] fileNames;
      
      if (Files.isDirectory(Path.of(fileName)))
      {
         File[] files = new File(fileName).listFiles();
         fileNames = new String[files.length];
         
         for (int i=0; i<files.length; i++)
            fileNames[i] = files[i].getAbsolutePath();            
      }
      else
      {
         fileNames = new String[] { fileName };
      }
      
      for (String fn : fileNames)
         process1(fn);
   }
 
   
   // Simple reorder
   private static void process1(String fileName) throws Exception 
   {
      System.out.println(fileName);
      FileInputStream fis = new FileInputStream(fileName);
      FileOutputStream fos = new FileOutputStream(fileName+".reordered");
      byte[] buf1 = new byte[100*1024*1024];
      byte[] buf2 = new byte[100*1024*1024];
      int read;
      final int nbFrames = getNbFrames(fileName);
      final int depth = getDepth(fileName);
      int n = 0;
      
      while ((read = fis.read(buf1)) > 0) 
      {
         final int szFrame = read / (nbFrames * depth);
         
         for (int i=0; i<depth*szFrame; i+=depth)
         {
            for (int j=0; j<nbFrames; j++)
            {
               for (int k=i; k<i+depth; k++) 
               {
                  buf2[n++] = buf1[szFrame*j+k];
                  //System.out.println(n+" "+(szFrame*j+k));
               }
            }                       
         }
         
         fos.write(buf2, 0, n);
      }
      
      fis.close();
      fos.close();
   }
  
  
   // Diff + reorder
   private static void process2(String fileName) throws Exception 
   {
      FileInputStream fis = new FileInputStream(fileName);
      FileOutputStream fos = new FileOutputStream(fileName+".diff");
      byte[] buf1 = new byte[100*1024*1024];
      byte[] buf2 = new byte[100*1024*1024];
      int read;
      final int nbFrames = getNbFrames(fileName);
      final int depth = getDepth(fileName);
      int n = 0;
      
      while ((read = fis.read(buf1)) > 0) 
      {
         final int szFrame = read / (nbFrames * depth);
         
         for (int i=0; i<depth*szFrame; i+=depth)
         {
            for (int j=0; j<nbFrames; j++)
            {
               int val = (buf1[szFrame*j]<<8) + buf1[szFrame*j+1];
               
               for (int k=i; k<i+depth; k+=2) 
               {
                  if (j == 0)
                  {
                     val = (buf1[k]<<8) + buf1[k+1];
                     buf2[n++] = (byte)(val>>8);
                     buf2[n++] = (byte)(val);
                  }
                  else
                  {
                     int val2 = (buf1[szFrame*j+k]<<8) + buf1[szFrame*j+k+1];
                     buf2[n++] = (byte) ((val2-val)>>8);
                     buf2[n++] = (byte) (val2-val);
                     val = val2;
                  }
                  //System.out.println(n+" "+(szFrame*j+k));
               }
            }                       
         }
         
         fos.write(buf2, 0, n);
      }
      
      fis.close();
      fos.close();
   }

   
   private static int getNbFrames(String name)
   {
      String[] tk = name.split("x");
      String s = tk[tk.length-2].strip();
      return Integer.parseInt(s);
   }

   
   private static int getDepth(String name)
   {
      String[] tk = name.split("x");
      String s = tk[tk.length-1].strip();
      return Integer.parseInt(s.substring(0, s.length()-1)) / 8;
   }
}

