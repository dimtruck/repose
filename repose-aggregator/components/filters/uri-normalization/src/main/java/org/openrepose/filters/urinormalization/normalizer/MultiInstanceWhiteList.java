package org.openrepose.filters.urinormalization.normalizer;

import org.openrepose.commons.utils.http.normal.ParameterFilter;
import org.openrepose.filters.urinormalization.config.HttpUriParameterList;
import org.openrepose.filters.urinormalization.config.UriParameter;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author zinic
 */
public class MultiInstanceWhiteList implements ParameterFilter {

   private final HttpUriParameterList parameterList;
   private final Map<String, Long> instanceMap;

   public MultiInstanceWhiteList(HttpUriParameterList parameterList) {
      this.parameterList = parameterList;

      instanceMap = new HashMap<String, Long>();
   }
   
   HttpUriParameterList getParameterList() {
       return parameterList;
   }

   @Override
   public boolean shouldAccept(String name) {
      
      if(parameterList==null){
         return false;
      }
      for (UriParameter parameter : parameterList.getParameter()) {
         final boolean matches = parameter.isCaseSensitive() ? name.equals(parameter.getName()) : name.equalsIgnoreCase(parameter.getName());

         if (matches) {
            return logHit(parameter);
         }
      }

      return false;
   }

   private boolean logHit(UriParameter parameter) {
      if (parameter.getMultiplicity() <= 0) {
         return true;
      }

      final Long hitCount = instanceMap.get(parameter.getName());
      final Long nextHitCount = hitCount != null ? hitCount + 1 : 1;

      if (nextHitCount <= parameter.getMultiplicity()) {
         instanceMap.put(parameter.getName(), nextHitCount);
         return true;
      }

      return false;
   }
}
