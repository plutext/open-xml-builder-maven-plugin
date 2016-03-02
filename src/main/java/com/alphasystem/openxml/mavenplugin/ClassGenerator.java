/**
 *
 */
package com.alphasystem.openxml.mavenplugin;

import com.sun.codemodel.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.alphasystem.openxml.mavenplugin.CodeModelUtil.*;
import static com.alphasystem.openxml.mavenplugin.FluentApiGenerator.*;
import static com.alphasystem.openxml.mavenplugin.ReflectionUtils.*;
import static com.sun.codemodel.ClassType.CLASS;
import static com.sun.codemodel.JExpr.*;
import static com.sun.codemodel.JMod.*;
import static java.lang.String.format;
import static java.lang.System.err;
import static org.apache.commons.lang.WordUtils.capitalize;
import static org.apache.commons.lang.WordUtils.uncapitalize;

/**
 * @author sali
 */
public class ClassGenerator {

    private static String getCreateMethodName(Class<?> srcClass) {
        String createMethodNamePrefix = "";
        Class<?> declaringClass = srcClass.getDeclaringClass();
        while (declaringClass != null) {
            createMethodNamePrefix = declaringClass.getSimpleName() + createMethodNamePrefix;
            declaringClass = declaringClass.getDeclaringClass();
        }
        return format("create%s%s", createMethodNamePrefix, srcClass.getSimpleName());
    }

    private static void addBuilderGetterMethod(JDefinedClass _class, JCodeModel codeModel, JDefinedClass builderClass,
                                               Class<?> paramType, int mods) {
        String methodName = format("get%s", _class.name());
        JType returnType = parseType(codeModel, _class.name());

        JMethod method = addMethod(mods, returnType, methodName, builderClass);
        method.body()._return(_new(returnType));

        method = addMethod(mods, returnType, methodName, builderClass);
        method.param(paramType, FIELD_NAME);
        JBlock body = method.body();
        body._return(_new(returnType).arg(FIELD_TYPE_REF));
    }

    private final JCodeModel codeModel;
    private final Class<?> srcClass;
    private final JDefinedClass enclosingClass;
    private final String superClassName;
    private final String sourcePackageName;
    private final JDefinedClass builderFactoryClass;
    private JBlock constructorBody;
    private JDefinedClass thisClass;
    private Map<String, PropertyInfo> classInfo = new LinkedHashMap<>();

    public ClassGenerator(JCodeModel codeModel, JDefinedClass enclosingClass, Class<?> srcClass, String superClassName, String sourcePackageName,
                          JDefinedClass builderFactoryClass) {
        this.codeModel = codeModel;
        this.enclosingClass = enclosingClass;
        this.srcClass = srcClass;
        this.superClassName = superClassName;
        this.sourcePackageName = sourcePackageName;
        this.builderFactoryClass = builderFactoryClass;
        classInfo.putAll(inspectClass(this.srcClass));
    }

    public JDefinedClass generate() {
        try {
            if (enclosingClass == null) {
                thisClass = codeModel._class(PUBLIC, getBuilderClassFqn(srcClass), CLASS);
            } else {
                thisClass = enclosingClass._class(PUBLIC | STATIC, format("%sBuilder", srcClass.getSimpleName()), CLASS);
            }
            thisClass.metadata = srcClass;
            thisClass.javadoc().add(format("Fluent API builder for <code>%s</code>.", srcClass.getName()));

            JClass superClass = parseClass(codeModel, superClassName).narrow(srcClass);
            // extends with "OpenXmlBuilder"
            thisClass._extends(superClass);

            // add the field
            // thisClass.field(PRIVATE, srcClass, FIELD_NAME);

            // add Constructor
            addConstructor();
            addOverloadedConstructor();

            // implement "createObject" method
            final JMethod method = thisClass.method(PROTECTED, srcClass, CREATE_OBJECT_METHOD_NAME);
            method.body()._return(invoke(builderFactoryClass.staticRef(OBJECT_FACTORY_FIELD_NAME), getCreateMethodName(srcClass)));

            // add fluent API methods
            classInfo.entrySet().forEach(entry -> processField(entry.getValue()));

            if (enclosingClass == null) {
                // add "get" method in builder class
                addBuilderGetterMethod(thisClass, codeModel, builderFactoryClass, srcClass, PUBLIC | STATIC);
            }
        } catch (JClassAlreadyExistsException e) {
            // ignore
        }
        return thisClass;
    }

    private void addConstructor() {
        JMethod constructor = thisClass.constructor(PUBLIC);
        constructor.javadoc().add("Initialize the underlying object.");
        JBlock constructorBody = constructor.body();
        constructorBody.invoke("this").arg(_null());
    }

    private void addOverloadedConstructor() {
        JMethod constructor = thisClass.constructor(PUBLIC);
        final JDocComment javadoc = constructor.javadoc();
        javadoc.addParam(FIELD_NAME).add("the given object");
        javadoc.add("Initialize the builder with given object.");
        constructor.param(parseType(codeModel, srcClass.getName()), FIELD_NAME);
        constructorBody = constructor.body();
        constructorBody.invoke("super").arg(FIELD_TYPE_REF);
    }

    private void processField(PropertyInfo propertyInfo) {
        final Field field = propertyInfo.getField();
        final boolean collectionType = isCollectionType(field);
        Method srcMethod = collectionType ? propertyInfo.getReadMethod() : propertyInfo.getWriteMethod();
        if (srcMethod == null) {
            throw new RuntimeException(format("Unable to find source method for field {%s} in class {%s}", propertyInfo.getFieldName(),
                    thisClass.name()));
        }
        String srcMethodName = srcMethod.getName();
        // name of builder method
        // if it is a collection type and src method name is "getContent" then the target method name will be "addContent"
        // if it is object type and src method name is "getContent" then the target method name will be "withContent"
        String targetMethodName = collectionType ? srcMethodName.replaceFirst("^get", "add") :
                srcMethodName.replaceFirst("^set", "with");

        Class<?> paramType = field.getType();
        if (collectionType) {
            paramType = getCollectionGenericType(field);
            if (paramType == null) {
                err.println(format("No collection type for field {%s} for class {%s}", propertyInfo.getFieldName(), thisClass.name()));
                return;
            }
        }

        JMethod method = addMethod(PUBLIC, thisClass, targetMethodName, thisClass);

        JVar param;
        if (collectionType) {
            param = method.varParam(paramType, PARAM_NAME);
        } else {
            param = method.param(paramType, PARAM_NAME);
        }
        final JBlock body = method.body();
        JBlock block = body;
        if (!paramType.isPrimitive() && !collectionType) {
            block = body._if(param.ne(_null()))._then();
        }
        if (collectionType) {
            invokeMethod(body, ADD_CONTENT_METHOD_NAME, FIELD_TYPE_REF.invoke(propertyInfo.getReadMethod().getName()), param);
        } else {
            invokeMethod(block, FIELD_TYPE_REF, propertyInfo.getWriteMethod().getName(), param);
        }
        body._return(_this());

        // overloaded method for fields with param type BigInteger
        if (isAssignableFrom(BigInteger.class, paramType)) {
            addBigIntegerOverloadedMethod(method.name(), propertyInfo.getWriteMethod());
        }

        // generates builder for any class which is part of our source package
        // if param type is ENUM then we do not do any thing else
        if (!paramType.isEnum() && paramType.getPackage().getName().equals(sourcePackageName)) {
            // we are about to generate builder for any field with data type of our source package
            // but if the field is non collection type and an inner class then we will generate inner builder
            if (!collectionType && paramType.getName().contains("$")) {
                addInnerBuilder(paramType, targetMethodName, propertyInfo);
            } else {
                ClassGenerator generator = new ClassGenerator(codeModel, null, paramType, superClassName,
                        sourcePackageName, builderFactoryClass);
                generator.generate();
            }
        }
    }

    private void addInnerBuilder(Class<?> paramType, String targetMethodName, PropertyInfo pi) {
        ClassGenerator generator = new ClassGenerator(codeModel, thisClass, paramType, superClassName,
                sourcePackageName, builderFactoryClass);
        final JDefinedClass innerClass = generator.generate();

        // add this inner builder construction method
        final JFieldVar field = thisClass.field(PRIVATE, innerClass, uncapitalize(innerClass.name()));
        constructorBody.assign(field, _new(innerClass).arg(refthis(FIELD_NAME).invoke(pi.getReadMethod().getName())));

        String builderMethodName = format("get%s", innerClass.name());

        // generate overloaded method, if the number of fields in the type is less then or equal to 4
        addOverloadMethod(paramType, field, targetMethodName);

        JMethod method = addMethod(PUBLIC, innerClass, builderMethodName, thisClass);
        method.body()._return(field);
    }

    private void addOverloadMethod(Class<?> paramType, JFieldVar fieldVar, String targetMethodName) {
        final Map<String, PropertyInfo> propertyInfoMap = inspectClass(paramType);
        if (propertyInfoMap.size() <= 4) {
            final JMethod method = addMethod(PUBLIC, thisClass, targetMethodName, thisClass);

            List<JVar> methodParams = new ArrayList<>();
            List<PropertyInfo> collectionFields = new ArrayList<>();
            for (Map.Entry<String, PropertyInfo> entry : propertyInfoMap.entrySet()) {
                final PropertyInfo pi = entry.getValue();
                final Field field = pi.getField();
                final Class<?> fieldType = field.getType();
                if (isCollectionType(field)) {
                    // collection type will be added as the last parameter as var args
                    collectionFields.add(pi);
                } else {
                    if (isAssignableFrom(BigInteger.class, fieldType)) {
                        methodParams.add(method.param(Long.class, pi.getFieldName()));
                    } else {
                        methodParams.add(method.param(fieldType, pi.getFieldName()));
                    }
                }
            }

            if (collectionFields.size() > 1) {
                throw new RuntimeException(format("More then one collection type fields in class {%s} of type {%s} of method {%s}",
                        thisClass.name(), paramType.getName(), targetMethodName));
            }

            if (!collectionFields.isEmpty()) {
                final PropertyInfo pi = collectionFields.get(0);
                final Field field = pi.getField();
                final Class<?> collectionGenericType = getCollectionGenericType(field);
                if (collectionGenericType == null) {
                    System.err.println(format("Unable to find collection generic type, field Name {%s}, filed type {%s} in class {%s}",
                            field.getName(), field.getType(), thisClass.name()));
                    collectionFields.clear();
                } else {
                    methodParams.add(method.varParam(collectionGenericType, field.getName()));
                }
            }

            final JBlock body = method.body();

            // initialize boolean variable
            JExpression init;
            JVar param = methodParams.get(0);
            boolean array = param.type().isArray();
            if (array) {
                init = invoke(HAS_CONTENT_MEHOD_NAME).arg(param);
            } else {
                init = param.ne(_null());
            }

            for (int i = 1; i < methodParams.size(); i++) {
                param = methodParams.get(i);
                array = param.type().isArray();
                if (array) {
                    init = init.cor(invoke(HAS_CONTENT_MEHOD_NAME).arg(param));
                } else {
                    init = init.cor(param.ne(_null()));
                }
            }

            body.decl(codeModel.BOOLEAN, INITIALIZED_VARIABLE_NAME).init(init);

            final JBlock ifParamBlock = body._if(INITIALIZED_TYPE_REF)._then();
            JInvocation invocation = null;
            for (JVar var : methodParams) {
                String varName = var.name();
                if (var.type().isArray()) {
                    // collection type
                    final PropertyInfo pi = collectionFields.get(0);
                    final Method readMethod = pi.getReadMethod();
                    String methodName = readMethod.getName().replaceFirst("^get", "add");
                    if (invocation == null) {
                        invocation = fieldVar.invoke(methodName).arg(var);
                    } else {
                        invocation = invocation.invoke(methodName).arg(var);
                    }
                } else {
                    varName = varName.startsWith("_") ? varName.substring(1) : varName;
                    String mn = format("with%s", capitalize(varName));
                    if (invocation == null) {
                        invocation = fieldVar.invoke(mn).arg(var);
                    } else {
                        invocation = invocation.invoke(mn).arg(var);
                    }
                }
            }
            if (invocation == null) {
                throw new RuntimeException(format("invocation is still null, Param Type: %s, Field: %s, Target Method Name: %s",
                        paramType.getName(), fieldVar.name(), targetMethodName));
            }

            ifParamBlock.invoke(targetMethodName).arg(invocation.invoke(GET_OBJECT_METHOD_NAME));
            body._return(_this());
        }
    }

    private void addBigIntegerOverloadedMethod(String withMethodName, Method setterMethod) {
        addOverloadedMethod(withMethodName, setterMethod, BigInteger.class, String.class);
        addOverloadedMethod(withMethodName, setterMethod, BigInteger.class, Long.class);
    }

    private void addOverloadedMethod(String withMethodName, Method setterMethod, Class<?> valueClass, Class<?> paramType) {
        // we can have over loaded by two way,
        // either we have a constructor with given "paramType"
        // or we have static "valueOf" method

        // look for constructor first
        boolean hasConstructor = false;
        try {
            hasConstructor = valueClass.getConstructor(paramType) != null;
        } catch (NoSuchMethodException e) {
            // not found
        }

        JMethod method = addMethod(PUBLIC, thisClass, withMethodName, thisClass);
        final JVar param = method.param(paramType, PARAM_NAME);
        String setterMethodName = addJavaDocComments(method, setterMethod);

        JBlock block = method.body();
        JBlock ifBodyBlock = block._if(param.ne(_null()))._then();
        JInvocation arg;
        final JClass type = parseClass(codeModel, valueClass);
        if (hasConstructor) {
            arg = _new(type).arg(param);
        } else {
            arg = type.staticInvoke("valueOf").arg(param);
        }
        invokeMethod(ifBodyBlock, FIELD_TYPE_REF, setterMethodName, arg);
        block._return(_this());
    }

    private String addJavaDocComments(JMethod method, Method setterMethod) {
        JDocComment javadoc = method.javadoc();
        String setterMethodName = setterMethod.getName();
        javadoc.add(format("Calls <code>%s</code> method.", setterMethodName));
        javadoc.addParam("value");
        javadoc.addReturn().add("reference to this");
        return setterMethodName;
    }

}