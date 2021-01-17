package com.just_graduate.smartcane.di

import com.just_graduate.smartcane.Repository
import com.just_graduate.smartcane.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { MainViewModel(get()) }
}

val repositoryModule = module{
    single{
        Repository()
    }
}