/*
 * Copyright 2020-2022, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nextflow.extension

import java.lang.reflect.Modifier

import groovy.runtime.metaclass.ChannelFactory
import groovy.runtime.metaclass.DelegatingPlugin
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowBroadcast
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Global
import nextflow.Session
import nextflow.plugin.Plugins
import nextflow.plugin.Scoped
import nextflow.script.ChannelOut
/**
 * Manage channel extensions and dispatch method invocations
 * to target class implementing the extension logic
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ChannelExtensionDelegate implements DelegatingPlugin {

    private static ChannelExtensionDelegate instance

    private Session getSession() { Global.getSession() as Session }

    final private Map<String, ChannelFactory> channelFactories = new HashMap<>()

    final private Map<String,Object> operatorExtensions = new HashMap<>()

    final private Map<String,Tuple2<String,Object>> aliasOperatorExtensions = new HashMap<>()

    final private Map<String,Tuple2<String,ChannelFactoryInstance>> aliasFactoryExtensions = new HashMap<>()

    private List<ChannelExtensionPoint> channelExtensionPoints

    private Set<String> OPERATOR_NAMES

    static ChannelExtensionDelegate INSTANCE() {
        if( instance != null )
            return instance
        return instance = new ChannelExtensionDelegate().install()
    }

    static void reset() {
        instance = null
    }

    ChannelExtensionDelegate install() {
        // add default operators
        final defaultOps = loadDefaultOperators()
        log.trace "Dataflow default extension methods: ${defaultOps.sort().join(',')}"
        final customOps = loadCustomOperators()
        if( customOps )
            log.debug "Dataflow custom extension methods: ${customOps.sort().join(',')}"
        OPERATOR_NAMES = Collections.unmodifiableSet(defaultOps + customOps)
        // configure as global instance
        return instance = this
    }

    private Set<String> loadDefaultOperators() {
        final result = getDeclaredExtensionMethods0(OperatorEx.class)
        for( String it : result )
            operatorExtensions.put(it, OperatorEx.instance)
        return result
    }

    ChannelExtensionDelegate loadChannelExtensionInPlugin(String pluginId, Map<String, String> methodAlias){
        findChannelExtensionPointInPluginId(pluginId).ifPresent({ ext ->
            loadChannelExtension(ext, methodAlias)
        })
        return instance = this
    }

    void loadChannelExtension(ChannelExtensionPoint ext, Map<String, String> methodAlias){
        final declaredOperators = getDeclaredExtensionMethods0(ext.getClass())
        final declaredFactories = getDeclaredFactoryExtensionMethods0(ext.getClass())
        methodAlias.each {entry ->
            String it = entry.key
            String alias = entry.value
            final tuple2 = aliasOperatorExtensions.get(alias)
            if( tuple2 ){
                throw new IllegalStateException("Operator '$alias' conflict - it's defined by plugin ${tuple2.v2.class.name}")
            }
            Object existing = operatorExtensions.get(alias)
            if (existing.is(OperatorEx.instance)) {
                throw new IllegalStateException("Operator '$it' is already defined as a built-in operator - Offending plugin class: $ext")
            } else if (existing != null) {
                if( existing.getClass().getName() != ext.getClass().getName() ) {
                    throw new IllegalStateException("Operator '$it' conflict - it's defined by plugin ${existing.getClass().getName()} and ${ext.getClass().getName()}")
                }
            }
            if( declaredOperators.contains(it)) {
                OPERATOR_NAMES = Collections.unmodifiableSet(OPERATOR_NAMES + [alias])
                aliasOperatorExtensions.put(alias, new Tuple2(it, ext))
            }else if( declaredFactories.contains(it) ){
                ChannelFactoryInstance factoryInstance = new ChannelFactoryInstance(null, ext)
                aliasFactoryExtensions.put(alias, new Tuple2(it, factoryInstance))
            }else{
                throw new IllegalStateException("Operator '$it' it isn't defined by plugin ${existing.getClass().getName()}")
            }
        }
    }

    private Set<String> loadCustomOperators() {
        final extensions = findChannelExtensionPoints0()
        final allNames = new HashSet()
        for( ChannelExtensionPoint ext : extensions ) {
            // find out the extension method names for the extension obj
            final names = getDeclaredExtensionMethods0(ext.getClass())
            // check if exists already and add it
            for( String it : names ) {
                final existing = operatorExtensions.get(it)
                if( existing.is(OperatorEx.instance) ) {
                    throw new IllegalStateException("Operator '$it' is already defined as a built-in operator - Offending plugin class: $ext")
                }
                else if( existing != null ) {
                    throw new IllegalStateException("Operator '$it' conflict - it's defined by plugin ${existing.getClass().getName()} and ${ext.getClass().getName()}")
                }
                else {
                    allNames.add(it)
                    operatorExtensions.put(it, ext)
                }
            }
        }

        return allNames
    }

    static private Set<String> getDeclaredExtensionMethods0(Class clazz) {
        def result = new HashSet<String>(30)
        def methods = clazz.getDeclaredMethods()
        for( def handle : methods ) {
            // skip non-public methods
            if( !Modifier.isPublic(handle.getModifiers()) ) continue
            // skip static methods
            if( Modifier.isStatic(handle.getModifiers()) ) continue
            // operator extension method must have a dataflow read channel type as first argument
            def params=handle.getParameterTypes()
            if( params.length>0 && isReadChannel(params[0]) )
                result.add(handle.name)
        }
        return result
    }

    static private Set<String> getDeclaredFactoryExtensionMethods0(Class clazz) {
        def result = new HashSet<String>(30)
        def methods = clazz.getDeclaredMethods()
        for( def handle : methods ) {
            // skip non-public methods
            if( !Modifier.isPublic(handle.getModifiers()) ) continue
            // skip static methods
            if( Modifier.isStatic(handle.getModifiers()) ) continue
            // factory extension method must have a dataflow write channel type as return
            def returnType =handle.getReturnType()
            if( isWriteChannel(returnType) )
                result.add(handle.name)
        }
        return result
    }

    static boolean isReadChannel(Class clazz) {
        DataflowReadChannel.class.isAssignableFrom(clazz)
    }

    static boolean isWriteChannel(Class clazz) {
        DataflowWriteChannel.class.isAssignableFrom(clazz)
    }

    Set<String> operatorNames() { OPERATOR_NAMES }

    boolean isExtensionMethod(Object obj, String name) {
        if( obj instanceof DataflowReadChannel || obj instanceof DataflowBroadcast || obj instanceof ChannelOut ) {
            return OPERATOR_NAMES.contains(name)
        }
        return false
    }

    Object invokeExtensionMethod(Object channel, String method, Object[] args) {
        final target = aliasOperatorExtensions.get(method)?.v2 ?: operatorExtensions.get(method)
        method = aliasOperatorExtensions.get(method)?.v1 ?: method
        if( target==null )
            throw new IllegalStateException("Missing target class for operator '$method'")
        if( target instanceof ChannelExtensionPoint )
            target.checkInit(getSession())
        new OpCall(target,channel,method,args).call()
    }

    def invokeFactoryExtensionMethod(String name, Object[] args){
        if( aliasFactoryExtensions.containsKey(name) ){
            def tuple2 = aliasFactoryExtensions.get(name)
            def factory = tuple2.v2
            return factory.invokeExtensionMethod(tuple2.v1, args)
        }
    }

    ChannelFactory getChannelFactory(String factoryScope) {
        if( channelFactories.containsKey(factoryScope) )
            return channelFactories.get(factoryScope)

        synchronized (channelFactories) {
            final result = createFactoryInstance0(factoryScope)
            channelFactories.put(factoryScope, result)
            return result
        }
    }

    /**
     * Create a {@link ChannelFactoryInstance} object for the given scope
     *
     * @param scope
     * @return The {@link ChannelFactoryInstance} instance holding the extensions matching the specified scope
     */
    protected ChannelFactoryInstance createFactoryInstance0(String scope) {
        final all = findChannelExtensionPoints0()
        log.trace "Loading channel factory extensions: $all"
        for( ChannelExtensionPoint it : all ) {
            final annot = it.getClass().getAnnotation(Scoped)
            if( annot && annot.value()==scope )
                return new ChannelFactoryInstance(scope, it)
        }
        return null
    }

    protected List<ChannelExtensionPoint> findChannelExtensionPoints0() {
        if( channelExtensionPoints==null )
            channelExtensionPoints = Plugins.getScopedExtensions(ChannelExtensionPoint).toList()
        return channelExtensionPoints
    }

    protected Optional<ChannelExtensionPoint> findChannelExtensionPointInPluginId(String pluginId) {
        Optional.ofNullable(Plugins.getExtensionsInPluginId(ChannelExtensionPoint, pluginId)?.first())
    }

    static void reloadExtensionPoints() {
        if( !instance )
            return
        instance.channelExtensionPoints=null
        instance.operatorExtensions.clear()
        instance.install()
    }
}
