package local.oss.chronicle.injection.components

import dagger.Component
import local.oss.chronicle.injection.modules.UITestAppModule
import javax.inject.Singleton

@Component(modules = [UITestAppModule::class])
@Singleton
interface UITestAppComponent : AppComponent {
    // Test injection methods can be added here as needed
}
