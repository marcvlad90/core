/*
 * Copyright 2012 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.shell.aesh;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.aesh.cl.CommandLine;
import org.jboss.aesh.cl.activation.OptionActivator;
import org.jboss.aesh.cl.builder.OptionBuilder;
import org.jboss.aesh.cl.completer.OptionCompleter;
import org.jboss.aesh.cl.converter.Converter;
import org.jboss.aesh.cl.exception.OptionParserException;
import org.jboss.aesh.cl.internal.ProcessedCommand;
import org.jboss.aesh.cl.internal.ProcessedOption;
import org.jboss.aesh.cl.parser.CommandLineParser;
import org.jboss.aesh.cl.validator.OptionValidator;
import org.jboss.aesh.cl.validator.OptionValidatorException;
import org.jboss.aesh.console.command.completer.CompleterInvocation;
import org.jboss.aesh.console.command.converter.ConverterInvocation;
import org.jboss.aesh.console.command.validator.ValidatorInvocation;
import org.jboss.forge.addon.convert.ConverterFactory;
import org.jboss.forge.addon.shell.aesh.completion.OptionCompleterFactory;
import org.jboss.forge.addon.shell.ui.ShellContext;
import org.jboss.forge.addon.shell.util.ShellUtil;
import org.jboss.forge.addon.ui.controller.CommandController;
import org.jboss.forge.addon.ui.hints.InputType;
import org.jboss.forge.addon.ui.input.InputComponent;
import org.jboss.forge.addon.ui.input.ManyValued;
import org.jboss.forge.addon.ui.input.SingleValued;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.util.InputComponents;
import org.jboss.forge.addon.ui.validation.UIValidationMessage;
import org.jboss.forge.addon.ui.validation.UIValidationMessage.Severity;

/**
 * Contains utility methods to parse command lines
 * 
 * @author <a href="mailto:stale.pedersen@jboss.org">Ståle W. Pedersen</a>
 * @author <a href="ggastald@redhat.com">George Gastaldi</a>
 */
public class CommandLineUtil
{
   private static final Logger logger = Logger.getLogger(CommandLineUtil.class.getName());

   private static final String ARGUMENTS_INPUT_NAME = "arguments";

   private final ConverterFactory converterFactory;

   public CommandLineUtil(ConverterFactory converterFactory)
   {
      this.converterFactory = converterFactory;
   }

   public CommandLineParser generateParser(CommandController command, ShellContext shellContext,
            Map<String, InputComponent<?, ?>> inputs)
   {
      ProcessedCommand processedCommand = generateCommand(command, shellContext, inputs);
      return new ForgeCommandLineParser(processedCommand, this, inputs);
   }

   private ProcessedCommand generateCommand(final CommandController command, final ShellContext shellContext,
            final Map<String, InputComponent<?, ?>> inputs)
   {
      UICommandMetadata metadata = command.getMetadata();
      ProcessedCommand parameter = new ProcessedCommand(ShellUtil.shellifyName(metadata.getName()),
               metadata.getDescription(), ForgeCommandValidator.INSTANCE);

      for (final InputComponent<?, ?> input : inputs.values())
      {
         final Object defaultValue = InputComponents.getValueFor(input);
         final boolean isMultiple = input instanceof ManyValued;
         final boolean hasValue = (InputComponents.getInputType(input) != InputType.CHECKBOX && !Boolean.class
                  .isAssignableFrom(input.getValueType()));
         try
         {
            OptionBuilder optionBuilder = new OptionBuilder();

            optionBuilder.name(input.getName())
                     .addDefaultValue(defaultValue == null ? null : defaultValue.toString())
                     .description(input.getLabel())
                     .hasMultipleValues(isMultiple)
                     .hasValue(hasValue)
                     .type(input.getValueType());

            if (input.isRequired())
            {
               optionBuilder.required(true).renderer(OptionRenderers.REQUIRED);
            }
            OptionCompleter<CompleterInvocation> completer = OptionCompleterFactory.getCompletionFor(
                     input, shellContext, converterFactory);
            optionBuilder.completer(completer);
            optionBuilder.activator(new OptionActivator()
            {
               @Override
               public boolean isActivated(ProcessedCommand processedCommand)
               {
                  return input.isEnabled();
               }
            }).validator(new OptionValidator<ValidatorInvocation<Object>>()
            {
               @Override
               public void validate(ValidatorInvocation<Object> validatorInvocation) throws OptionValidatorException
               {
                  Object value = validatorInvocation.getValue();
                  InputComponents.setValueFor(converterFactory, input, value);
                  for (UIValidationMessage message : command.validate(input))
                  {
                     if (message.getSource() == input && message.getSeverity() == Severity.ERROR)
                     {
                        throw new OptionValidatorException(message.getDescription());
                     }
                  }
               }
            }).converter(new Converter<Object, ConverterInvocation>()
            {
               @Override
               public Object convert(ConverterInvocation converterInvocation) throws OptionValidatorException
               {
                  String value = converterInvocation.getInput();
                  return InputComponents.convertToUIInputValue(converterFactory, input, value);
               }
            }).valueSeparator(' ');

            if (input.getShortName() != InputComponents.DEFAULT_SHORT_NAME)
            {
               optionBuilder.shortName(input.getShortName());
            }
            ProcessedOption option = optionBuilder.create();
            if (ARGUMENTS_INPUT_NAME.equals(input.getName()))
            {
               parameter.setArgument(option);
            }
            else
            {
               parameter.addOption(option);
            }
         }
         catch (OptionParserException e)
         {
            logger.log(Level.SEVERE, "Error while parsing command option", e);
         }
      }
      return parameter;
   }

   public Map<String, InputComponent<?, ?>> populateUIInputs(CommandLine commandLine,
            Map<String, InputComponent<?, ?>> inputs)
   {
      Map<String, InputComponent<?, ?>> populatedInputs = new HashMap<>();
      for (Entry<String, InputComponent<?, ?>> entry : inputs.entrySet())
      {
         String name = entry.getKey();
         InputComponent<?, ?> input = entry.getValue();
         if (ARGUMENTS_INPUT_NAME.equals(name))
         {
            InputComponents.setValueFor(converterFactory, input, commandLine.getArgument().getValue());
            populatedInputs.put(name, input);
         }
         if (commandLine.hasOption(name))
         {
            if (input instanceof ManyValued)
            {
               InputComponents.setValueFor(converterFactory, input, commandLine.getOptionValues(input.getName()));
               populatedInputs.put(name, input);
            }
            else if (input instanceof SingleValued)
            {
               InputComponents.setValueFor(converterFactory, input, commandLine.getOptionValue(input.getName()));
               populatedInputs.put(name, input);
            }
         }
      }
      return populatedInputs;
   }
}
