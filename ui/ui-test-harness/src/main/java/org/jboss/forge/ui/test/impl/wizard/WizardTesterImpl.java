/*
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.ui.test.impl.wizard;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

import javax.enterprise.inject.Vetoed;

import org.jboss.forge.addon.convert.ConverterFactory;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.ui.UICommand;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.controller.CommandExecutionListener;
import org.jboss.forge.addon.ui.impl.context.UIExecutionContextImpl;
import org.jboss.forge.addon.ui.input.InputComponent;
import org.jboss.forge.addon.ui.result.NavigationResult;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.util.InputComponents;
import org.jboss.forge.addon.ui.wizard.UIWizard;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.ui.test.impl.UIBuilderImpl;
import org.jboss.forge.ui.test.impl.UIContextImpl;
import org.jboss.forge.ui.test.impl.UIValidationContextImpl;

/**
 * This class eases the testing of Wizards
 * 
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 * 
 */
@Vetoed
public class WizardTesterImpl<W extends UIWizard>
{
   private final AddonRegistry addonRegistry;

   private final LinkedList<UIBuilderImpl> pages = new LinkedList<UIBuilderImpl>();

   private final Stack<Class<? extends UICommand>> subflows = new Stack<Class<? extends UICommand>>();

   private final UIContextImpl context;

   private final Class<W> wizardClass;

   public WizardTesterImpl(Class<W> wizardClass, AddonRegistry addonRegistry, UIContextImpl contextImpl)
   {
      this.addonRegistry = addonRegistry;
      this.context = contextImpl;
      this.wizardClass = wizardClass;
   }

   public void setInitialSelection(Resource<?>... selection)
   {
      context.setInitialSelection(selection);
   }

   public void launch() throws Exception
   {
      pages.add(createBuilder(wizardClass));
   }

   @SuppressWarnings("unchecked")
   public void next() throws Exception
   {
      if (!canFlipToNextPage())
      {
         throw new IllegalStateException("Wizard is already on the last page");
      }
      UIBuilderImpl currentBuilder = getCurrentBuilder();
      NavigationResult result = ((UIWizard) currentBuilder.getCommand()).next(context);
      Class<? extends UICommand>[] successors = result.getNext();
      final Class<? extends UICommand> successor;
      if (successors == null)
      {
         successor = subflows.pop();
      }
      else
      {
         successor = successors[0];
         for (int i = 1; i < successors.length; i++)
         {
            subflows.add(successors[i]);
         }
      }
      UIBuilderImpl nextBuilder = createBuilder((Class<W>) successor);
      pages.add(nextBuilder);
   }

   public void previous() throws Exception
   {
      if (!canFlipToPreviousPage())
      {
         throw new IllegalStateException("Wizard is already on the first page");
      }
      pages.removeLast();
   }

   public boolean canFlipToNextPage()
   {
      UIBuilderImpl currentBuilder = getCurrentBuilder();
      try
      {
         boolean result;
         NavigationResult next = ((UIWizard) currentBuilder.getCommand()).next(context);
         if (next == null)
         {
            result = !subflows.isEmpty();
         }
         else
         {
            result = true;
         }
         return result;
      }
      catch (Exception e)
      {
         throw new IllegalStateException(e);
      }
   }

   public boolean canFlipToPreviousPage()
   {
      return pages.size() > 1;
   }

   public boolean canFinish()
   {
      return getValidationErrors().isEmpty() && !canFlipToNextPage();
   }

   public boolean isValid()
   {
      return getValidationErrors().isEmpty();
   }

   public List<String> getValidationErrors()
   {
      return getValidationErrors(getCurrentBuilder());
   }

   public List<Result> finish() throws Exception
   {
      return finish(null);
   }

   public List<Result> finish(CommandExecutionListener listener) throws Exception
   {
      try
      {
         List<Result> results = new ArrayList<Result>();
         for (UIBuilderImpl builder : pages)
         {
            // validate before execute
            List<String> errors = getValidationErrors(builder);
            if (!errors.isEmpty())
            {
               throw new IllegalStateException(errors.toString());
            }
         }
         UIExecutionContext executionContext = new UIExecutionContextImpl(context, null);
         // All good. Hit it !
         for (UIBuilderImpl builder : pages)
         {
            UICommand wizard = builder.getCommand();
            try
            {
               if (listener != null)
               {
                  listener.preCommandExecuted(wizard, executionContext);
               }
               Result result = wizard.execute(executionContext);
               if (listener != null)
               {
                  listener.postCommandExecuted(wizard, executionContext, result);
               }
               results.add(result);
            }
            catch (Exception e)
            {
               if (listener != null)
               {
                  listener.postCommandFailure(wizard, executionContext, e);
               }
               throw e;
            }
         }

         return results;
      }
      finally
      {
         context.close();
      }
   }

   private UIBuilderImpl getCurrentBuilder()
   {
      return pages.peekLast();
   }

   private UIBuilderImpl createBuilder(Class<W> wizardClass) throws Exception
   {
      W wizard = addonRegistry.getServices(wizardClass).get();
      UIBuilderImpl builder = new UIBuilderImpl(context, wizard);
      wizard.initializeUI(builder);
      return builder;
   }

   @SuppressWarnings("unchecked")
   public void setValueFor(String property, Object value)
   {
      UIBuilderImpl currentBuilder = getCurrentBuilder();
      InputComponent<?, ?> input = currentBuilder.getComponentNamed(property);
      if (input == null)
      {
         throw new IllegalArgumentException("Property " + property + " not found for current wizard page");
      }
      InputComponents.setValueFor(getConverterFactory(), (InputComponent<?, Object>) input, value);
   }

   public InputComponent<?, ?> getInputComponent(String property)
   {
      UIBuilderImpl currentBuilder = getCurrentBuilder();
      return currentBuilder.getComponentNamed(property);
   }

   private List<String> getValidationErrors(UIBuilderImpl builder)
   {
      UICommand currentWizard = builder.getCommand();
      UIValidationContextImpl validationContext = new UIValidationContextImpl(context);

      for (InputComponent<?, ?> input : builder.getInputs())
      {
         input.validate(validationContext);
      }

      currentWizard.validate(validationContext);
      return validationContext.getErrors();
   }

   private ConverterFactory getConverterFactory()
   {
      return addonRegistry.getServices(ConverterFactory.class).get();
   }

   public boolean isEnabled()
   {
      return getCurrentBuilder().getCommand().isEnabled(context);
   }
}
