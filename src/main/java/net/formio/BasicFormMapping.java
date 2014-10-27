/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.formio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.formio.binding.BoundValuesInfo;
import net.formio.binding.FilledData;
import net.formio.binding.InstanceHoldingInstantiator;
import net.formio.binding.Instantiator;
import net.formio.binding.ParseError;
import net.formio.data.RequestContext;
import net.formio.format.Formatter;
import net.formio.internal.FormUtils;
import net.formio.servlet.ServletRequestParams;
import net.formio.upload.MaxSizeExceededError;
import net.formio.upload.RequestProcessingError;
import net.formio.upload.UploadedFile;
import net.formio.validation.ConstraintViolationMessage;
import net.formio.validation.ValidationResult;

/**
 * Default implementation of {@link FormMapping}. Immutable when not filled.
 * After the filling, new instance of mapping is created and its immutability 
 * depends on the character of filled data.
 * 
 * @author Radek Beran
 */
class BasicFormMapping<T> implements FormMapping<T> {

	final String path;
	final Class<T> dataClass;
	final Instantiator<T> instantiator;
	final Config config;
	final boolean userDefinedConfig;
	final T filledObject;
	
	/** Mapping simple property names to fields. */
	final Map<String, FormField<?>> fields;
	/** Mapping simple property names to nested mappings. Property name is a part of full path of nested mapping. */
	final Map<String, FormMapping<?>> nested;
	final ValidationResult validationResult;
	final boolean required;
	final boolean secured;

	/**
	 * Construct the mapping from given builder.
	 * @param builder
	 */
	BasicFormMapping(BasicFormMappingBuilder<T> builder) {
		if (builder.config == null) throw new IllegalArgumentException("config cannot be null");
		this.config = builder.config;
		this.userDefinedConfig = builder.userDefinedConfig;
		this.path = builder.path;
		this.dataClass = builder.dataClass;
		this.instantiator = builder.instantiator;
		this.filledObject = builder.filledObject;
		this.secured = builder.secured;
		if (this.dataClass == null) throw new IllegalStateException("data class must be filled before configuring fields");
		this.fields = configuredFields(builder.fields, builder.config);
		this.validationResult = builder.validationResult;
		this.nested = Clones.mappingsWithPropagatedConfig(builder.nested, builder.dataClass, builder.config);
		this.required = false;
	}
	
	/**
	 * Returns copy with given path prefix prepended.
	 * @param src
	 * @param pathPrefix
	 */
	BasicFormMapping(BasicFormMapping<T> src, String pathPrefix) {
		if (pathPrefix == null) throw new IllegalArgumentException("pathPrefix cannot be null");
		this.config = src.getConfig();
		this.userDefinedConfig = src.isUserDefinedConfig();
		String newMappingPath = null;
		if (!pathPrefix.isEmpty()) {
			newMappingPath = pathPrefix + Forms.PATH_SEP + src.getName();
		} else {
			newMappingPath = src.getName();
		}
		this.path = newMappingPath;
		this.dataClass = src.getDataClass();
		this.instantiator = src.getInstantiator();
		this.filledObject = src.getFilledObject();
		this.secured = src.secured;
		this.fields = Clones.fieldsWithPrependedPathPrefix(src.fields, pathPrefix, newMappingPath);
		this.nested = Clones.mappingsWithPrependedPathPrefix(src.nested, pathPrefix);
		this.validationResult = src.getValidationResult();
		this.required = src.required;
	}
	
	BasicFormMapping(BasicFormMapping<T> src, int index, String pathPrefix) {
		if (pathPrefix == null) throw new IllegalArgumentException("pathPrefix cannot be null");
		this.config = src.getConfig();
		this.userDefinedConfig = src.isUserDefinedConfig();
		if (!src.path.startsWith(pathPrefix))
			throw new IllegalStateException("Mapping path '" + src.path + "' must start with prefix '" + pathPrefix + ".'");
		String newMappingPath = pathPrefix + "[" + index + "]" + src.path.substring(pathPrefix.length());
		this.path = newMappingPath;
		this.dataClass = src.dataClass;
		this.instantiator = src.instantiator;
		this.filledObject = src.filledObject;
		this.secured = src.secured;
		this.fields = Clones.fieldsWithIndexAfterPathPrefix(src.fields, index, pathPrefix);
		this.nested = Clones.mappingsWithIndexAfterPathPrefix(src.nested, index, pathPrefix);
		this.validationResult = src.validationResult;
		this.required = src.required;
	}
	
	/**
	 * Returns copy with given config.
	 * @param src
	 * @param config
	 * @param required
	 */
	BasicFormMapping(BasicFormMapping<T> src, Config config, boolean required) {
		if (config == null) throw new IllegalArgumentException("config cannot be null");
		this.config = config;
		this.userDefinedConfig = true;
		this.path = src.path;
		this.dataClass = src.dataClass;
		this.instantiator = src.instantiator;
		if (this.dataClass == null) throw new IllegalStateException("data class must be filled before configuring fields");
		this.fields = configuredFields(src.fields, config);
		this.validationResult = src.validationResult;
		this.filledObject = src.filledObject;
		this.secured = src.secured;
		this.nested = Clones.mappingsWithPropagatedConfig(src.nested, src.dataClass, config);
		this.required = required;
	}

	@Override
	public String getName() {
		return path;
	}

	@Override
	public Class<T> getDataClass() {
		return dataClass;
	}
	
	@Override
	public Instantiator<T> getInstantiator() {
		return instantiator;
	}

	@Override
	public ValidationResult getValidationResult() {
		return validationResult;
	}
	
	/**
	 * Returns form fields. Can be used in template to construct markup of form fields.
	 * @return
	 */
	@Override
	public Map<String, FormField<?>> getFields() {
		return fields;
	}
	
	@Override
	public <U> FormField<U> getField(Class<U> dataClass, String propertyName) {
		FormField<?> field = getFields().get(propertyName);
		if (field != null) {
			Object filledObject = field.getFilledObject();
			if (filledObject != null && !dataClass.isAssignableFrom(filledObject.getClass())) {
				throw new IllegalStateException("Type of value in field '" + propertyName + 
					"' is not compatible with requested type " + dataClass.getName());
			}
		}
		return (FormField<U>)field;
	}
	
	/**
	 * Returns nested mapping for nested complex objects.
	 * @return
	 */
	@Override
	public Map<String, FormMapping<?>> getNested() {
		return Collections.unmodifiableMap(nested);
	}
	
	@Override
	public <U> FormMapping<U> getNestedByProperty(Class<U> dataClass, String propertyName) {
		Map<String, FormMapping<?>> nestedMappings = getNested();
		FormMapping<?> mapping = nestedMappings.get(propertyName);
		if (mapping != null) {
			if (!dataClass.isAssignableFrom(mapping.getDataClass())) {
				throw new IllegalStateException("Type of object in nested mapping '" + propertyName + 
					"' is not compatible with requested type " + dataClass.getName());
			}
		}
		return (FormMapping<U>)mapping;
	}
	
	@Override
	public List<FormMapping<T>> getList() {
		return Collections.<FormMapping<T>>emptyList();
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, Locale locale, RequestContext ctx) {
		return fillInternal(editedObj, locale, ctx).build(this.getConfig());
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, Locale locale) {
		return fill(editedObj, locale, null);
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, RequestContext ctx) {
		return fill(editedObj, getDefaultLocale(), ctx);
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj) {
		return fill(editedObj, getDefaultLocale());
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, Locale locale, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, Locale locale, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, (T)null, ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, Class<?>... validationGroups) {
		return bind(paramsProvider, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, getDefaultLocale(), ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, T instance, Class<?>... validationGroups) {
		return bind(paramsProvider, instance, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, T instance, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, getDefaultLocale(), instance, ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(RequestParams paramsProvider, Locale locale, T instance, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, instance, (RequestContext)null, validationGroups);
	}

	@Override
	public FormData<T> bind(final RequestParams paramsProvider, final Locale locale, final T instance, final RequestContext context, final Class<?>... validationGroups) {
		if (paramsProvider == null) throw new IllegalArgumentException("paramsProvider cannot be null");
		RequestContext ctx = context;
		if (ctx == null && paramsProvider instanceof ServletRequestParams) {
			// fallback to ctx retrieved from ServletRequestParams, so the user need not to specify ctx explicitly for bind method
			ctx = ((ServletRequestParams)paramsProvider).getRequestContext();
		}
		
		final RequestProcessingError error = paramsProvider.getRequestError();
		Map<String, BoundValuesInfo> values = prepareValuesToBindForFields(paramsProvider, locale);
		
		// binding (and validating) data from paramsProvider to objects for nested mappings
		// and adding it to available values to bind
		Map<String, FormData<?>> nestedFormData = loadDataForMappings(nested, paramsProvider, locale, instance, ctx, validationGroups);
		for (Map.Entry<String, FormData<?>> e : nestedFormData.entrySet()) {
			values.put(e.getKey(), BoundValuesInfo.getInstance(
				new Object[] { e.getValue().getData() } , 
				(String)null, 
				(Formatter<Object>)null,
				locale));
		}
		
		if (!(error instanceof MaxSizeExceededError) && this.secured) {
			// Must be executed after processing of nested mappings
			AuthTokens.verifyAuthToken(ctx, this.getConfig().getTokenAuthorizer(), getRootMappingPath(), paramsProvider, isRootMapping());
		}
		
		// binding data from "values" to resulting object for this mapping
		Instantiator<T> instantiator = this.instantiator;
		if (instance != null) {
			// use instance already prepared by client which the client wish to fill
			instantiator = new InstanceHoldingInstantiator<T>(instance);
		}
		final FilledData<T> filledObject = this.getConfig().getBinder().bindToNewInstance(this.dataClass, instantiator, values);
		
		// validation of resulting object for this mapping
		List<RequestProcessingError> requestFailures = new ArrayList<RequestProcessingError>();
		if (error != null) {
			requestFailures.add(error);
		}
		ValidationResult validationRes = this.getConfig().getBeanValidator().validate(
			filledObject.getData(), 
			this.path, 
			requestFailures, 
			FormUtils.flatten(filledObject.getPropertyBindErrors().values()),
			locale,
			validationGroups);
		return new FormData<T>(filledObject.getData(), 
			Clones.mergedValidationResults(validationRes, nestedFormData));
	}

	@Override
	public String getLabelKey() {
		return FormUtils.labelKeyForName(this.path);
	}
	
	/**
	 * Object filled in this mapping.
	 * @return
	 */
	@Override
	public T getFilledObject() {
		return this.filledObject;
	}
	
	@Override
	public Config getConfig() {
		return config;
	}
	
	@Override
	public boolean isUserDefinedConfig() {
		return userDefinedConfig;
	}
	
	@Override
	public String toString() {
		return toString("");
	}
	
	/**
	 * Returns copy of this mapping with new path that has given prefix prepended.
	 * Given prefix is applied to all nested mappings recursively.
	 * @param pathPrefix
	 * @return
	 */
	@Override
	public BasicFormMapping<T> withPathPrefix(String pathPrefix) {
		return new BasicFormMapping<T>(this, pathPrefix);
	}
	
	@Override
	public BasicFormMapping<T> withIndexAfterPathPrefix(int index, String prefix) {
		return new BasicFormMapping<T>(this, index, prefix);
	}
	
	@Override
	public BasicFormMapping<T> withConfig(Config config, boolean required) {
		return new BasicFormMapping<T>(this, config, required);
	}
	
	@Override
	public String toString(String indent) {
		return new MappingStringBuilder<T>(
			getDataClass(), 
			path,
			fields,
			nested, 
			getList()).build(indent);
	}
	
	@Override
	public boolean isRequired() {
		return this.required;
	}
	
	Map<String, FormData<?>> loadDataForMappings(
		Map<String, FormMapping<?>> mappings, 
		RequestParams paramsProvider,
		Locale locale,
		T instance,
		RequestContext ctx,
		Class<?> ... validationGroups) {
		final Map<String, FormData<?>> dataMap = new LinkedHashMap<String, FormData<?>>();
		// Transformation from ? to Object (to satisfy generics)
		final Map<String, FormMapping<?>> inputMappings = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> e : mappings.entrySet()) {
			inputMappings.put(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, FormMapping<?>> e : inputMappings.entrySet()) {
			Object nestedInstance = null;
			if (instance != null) {
				nestedInstance = nestedData(e.getKey(), instance); 
			}
			FormMapping<Object> mapping = (FormMapping<Object>)e.getValue();
			FormData<Object> formData = mapping.bind(paramsProvider, locale, nestedInstance, ctx, validationGroups);
			dataMap.put(e.getKey(), formData);
		}
		// Transformation from Object to ? (to satisfy generics)
		final Map<String, FormData<?>> outputData = new LinkedHashMap<String, FormData<?>>();
		for (Map.Entry<String, FormData<?>> e : dataMap.entrySet()) {
			outputData.put(e.getKey(), e.getValue());
		}
		return outputData;
	}
	
	BasicFormMappingBuilder<T> fillInternal(FormData<T> editedObj, Locale locale, RequestContext ctx) {
		Map<String, FormMapping<?>> newNestedMappings = fillNestedMappings(editedObj, locale, ctx);
		
		// Preparing values for this mapping
		Map<String, Object> propValues = gatherPropertyValues(editedObj.getData(), FormUtils.getPropertiesFromFields(fields), ctx);
		
		// Fill the definitions of fields of this mapping with prepared values
		Map<String, FormField<?>> filledFields = fillFields(
			propValues, 
			editedObj.getValidationResult() != null && editedObj.getValidationResult().getFieldMessages() != null ?
				editedObj.getValidationResult().getFieldMessages() : new LinkedHashMap<String, List<ConstraintViolationMessage>>(),
			-1, 
			locale);

		// Returning copy of this form that is filled with form data
		BasicFormMappingBuilder<T> builder = null;
		if (this.secured) {
			builder = Forms.basicSecured(getDataClass(), this.path, this.instantiator).fields(filledFields);
		} else {
			builder = Forms.basic(getDataClass(), this.path, this.instantiator).fields(filledFields);
		}
		builder.nested = newNestedMappings;
		builder.validationResult = editedObj.getValidationResult();
		builder.filledObject = editedObj.getData();
		return builder;
	}
	
	/**
	 * Gather values of object's properties.
	 * @param object
	 * @param allowedProperties set of allowed properties, does not influence order of returned entries
	 * @param ctx request context
	 * @return
	 */
	Map<String, Object> gatherPropertyValues(T object, Set<String> allowedProperties, RequestContext ctx) {
		Map<String, Object> propValues = new LinkedHashMap<String, Object>();
		Map<String, Object> beanValues = this.getConfig().getBeanExtractor().extractBean(object, allowedProperties);
		propValues.putAll(beanValues);
		if (isRootMapping() && secured) {
			propValues.put(Forms.AUTH_TOKEN_FIELD_NAME, 
				AuthTokens.generateAuthToken(ctx, this.config.getTokenAuthorizer(), getRootMappingPath()));
		}
		return Collections.unmodifiableMap(propValues);
	}
	
	boolean isRootMapping() {
		return !this.path.contains(Forms.PATH_SEP);
	}
	
	String getRootMappingPath() {
		String p = this.path;
		int idxOfSep = p.indexOf(Forms.PATH_SEP);
		if (idxOfSep >= 0) {
			p = p.substring(0, idxOfSep);
		}
		return p;
	}

	Map<String, FormField<?>> fillFields(
		Map<String, Object> propValues, 
		Map<String, List<ConstraintViolationMessage>> fieldMsgs, 
		int indexInList, 
		Locale locale) {
		Map<String, FormField<?>> filledFields = new LinkedHashMap<String, FormField<?>>();
		// For each field from form definition, let's fill this field with value -> filled form field
		for (Map.Entry<String, FormField<?>> fieldDefEntry : this.fields.entrySet()) {
			final String propertyName = fieldDefEntry.getKey();
			if (indexInList >= 0 && Forms.AUTH_TOKEN_FIELD_NAME.equals(propertyName)) {
				if (isRootMapping() && this.secured) {
					throw new UnsupportedOperationException("Verification of authorization token is not supported "
						+ "in root list mapping. Please create SINGLE root mapping with nested list mapping.");
				}
			}
			
			final FormField<?> field = fieldDefEntry.getValue();
			Object value = propValues.get(propertyName);
			String fieldName = field.getName();
			if (indexInList >= 0) {
				fieldName = FormUtils.pathWithIndexBeforeLastProperty(field.getName(), indexInList);
			}
			List<ConstraintViolationMessage> fieldMessages = fieldMsgs.get(fieldName);
			String preferedStringValue = null;
			if (fieldMessages != null && !fieldMessages.isEmpty()) {
				preferedStringValue = getOriginalStringValueFromParseError(fieldMessages);
			}
			final FormField<?> filledField = createFormField(fieldName, (FormField<Object>)field, value, locale, preferedStringValue);
			filledFields.put(propertyName, filledField);
		}
		filledFields = Collections.unmodifiableMap(filledFields);
		return filledFields;
	}

	Map<String, FormMapping<?>> fillNestedMappings(FormData<T> editedObj, Locale locale, RequestContext ctx) {
		Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		// For each definition of nested mapping, fill this mapping with edited data -> filled mapping
		for (Map.Entry<String, FormMapping<?>> e : this.nested.entrySet()) {
			// nested data - nested object or list of nested objects in case of mapping to list
			Object data = nestedData(e.getKey(), editedObj.getData());
			FormData<Object> formData = new FormData<Object>(data, editedObj.getValidationResult()); // the outer report is propagated to nested
			FormMapping<Object> newMapping = (FormMapping<Object>)e.getValue();
			newNestedMappings.put(e.getKey(), newMapping.fill(formData, locale, ctx));
		}
		return newNestedMappings;
	}
	
	/**
	 * Returns nested object extracted as value of given property of given data.
	 * @param propName
	 * @param data
	 * @return
	 */
	<U> U nestedData(String propName, T data) {
		Map<String, Object> props = this.getConfig().getBeanExtractor().extractBean(data, Collections.singleton(propName));
		return (U)props.get(propName); // can be null if nested object is not required
	}

	/**
	 * Converts parameters from request (RequestParams) using field definitions and given locale
	 * to descriptions of values for individual properties, ready to bind to properties of form data object
	 * via binder.
	 * @param paramsProvider
	 * @param locale
	 * @return
	 */
	private Map<String, BoundValuesInfo> prepareValuesToBindForFields(RequestParams paramsProvider, Locale locale) {
		Map<String, BoundValuesInfo> values = new LinkedHashMap<String, BoundValuesInfo>();
		// Get values for each defined field
		for (Map.Entry<String, FormField<?>> e : fields.entrySet()) {
			FormField<?> field = e.getValue();
			String formPrefixedName = field.getName(); // already prefixed with form name
			if (!formPrefixedName.startsWith(this.path + Forms.PATH_SEP)) {
				throw new IllegalStateException("Field name '"
						+ formPrefixedName + "' not prefixed with path '"
						+ this.path + "'");
			}
			
			Object[] paramValues = null;
			UploadedFile[] files = paramsProvider.getUploadedFiles(formPrefixedName);
			if (files == null || files.length == 0) { 
				files = paramsProvider.getUploadedFiles(formPrefixedName + "[]");
			}
			if (files != null && files.length > 0) {
				// non-empty files array returned
				paramValues = files;
			} else {
				String[] strValues = paramsProvider.getParamValues(formPrefixedName);
				if (strValues == null) strValues = paramsProvider.getParamValues(formPrefixedName + "[]");
				if (this.getConfig().isInputTrimmed()) {
					strValues = FormUtils.trimValues(strValues);
				}
				paramValues = strValues;
			}
			String propertyName = e.getKey();
			values.put(propertyName, BoundValuesInfo.getInstance(
			  paramValues, field.getPattern(), field.getFormatter(), locale));
		}
		return values;
	}
	
	/**
	 * Returns copy of form fields that are updated with static information from configuration
	 * (like required flags). 
	 * @param sourceFields
	 * @param cfg
	 * @return
	 */
	private Map<String, FormField<?>> configuredFields(Map<String, FormField<?>> sourceFields, Config cfg) {
		if (cfg == null) throw new IllegalArgumentException("cfg cannot be null");
		if (this.getDataClass() == null) throw new IllegalStateException("data class cannot be null");
		
		Map<String, FormField<?>> fields = new LinkedHashMap<String, FormField<?>>();
		if (sourceFields != null) {
			for (Map.Entry<String, FormField<?>> e : sourceFields.entrySet()) {
				FormField<?> f = createFormField(cfg, e);
				fields.put(e.getKey(), f);
			}
		}
		return Collections.unmodifiableMap(fields);
	}
	
	private <U> FormField<U> createFormField(String fieldName, final FormField<U> field, U value, Locale locale, String preferedStringValue) {
		return FormFieldImpl.<U>getFilledInstance(
			fieldName, field.getType(), field.getPattern(), field.getFormatter(), field.getProperties(),
			FormUtils.<U>convertObjectToList(value), locale, this.getConfig().getFormatters(), preferedStringValue);
	}

	private <U> FormField<U> createFormField(Config cfg, Map.Entry<String, FormField<?>> e) {
		boolean requiredConstraintPresent = cfg.getBeanValidator().isRequired(this.getDataClass(), e.getKey());
		Boolean required = null;
		if (requiredConstraintPresent) {
			required = Boolean.TRUE;
		} // else not specified, required remains null
		return new FormFieldImpl<U>((FormField<U>)e.getValue(), required);
	}
	
	private Locale getDefaultLocale() {
		return Locale.getDefault();
	}
	
	private String getOriginalStringValueFromParseError(List<ConstraintViolationMessage> fieldMessages) {
		String value = null;
		if (fieldMessages != null) {
			for (ConstraintViolationMessage msg : fieldMessages) {
				if (value == null && msg.getMsgArgs() != null) {
					value = (String)msg.getMsgArgs().get(ParseError.MSG_ARG_VALUE_AS_STRING);
				}
			}
		}
		return value;
	}

}
