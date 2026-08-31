const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');

module.exports = (env, argv) => {
  const isProd = argv.mode === 'production';

  return {
    entry: './src/main.tsx',
    output: {
      path: isProd
        ? path.resolve(__dirname, '../src/main/resources/static')
        : path.resolve(__dirname, 'dist'),
      filename: isProd ? 'bundle.[contenthash].js' : 'bundle.js',
      clean: true,
      publicPath: '/',
    },
    resolve: {
      extensions: ['.tsx', '.ts', '.js', '.jsx'],
    },
    module: {
      rules: [
        {
          test: /\.tsx?$/,
          use: {
            loader: 'ts-loader',
            options: {
              configFile: 'tsconfig.app.json',
              transpileOnly: true,
            },
          },
          exclude: /node_modules/,
        },
        {
          test: /\.css$/,
          use: isProd
            ? [MiniCssExtractPlugin.loader, 'css-loader']
            : ['style-loader', 'css-loader'],
        },
        {
          test: /\.(png|jpg|jpeg|gif|ico|svg)$/,
          type: 'asset/resource',
        },
      ],
    },
    plugins: [
      new HtmlWebpackPlugin({
        template: './index.html',
      }),
      ...(isProd
        ? [
            new MiniCssExtractPlugin({ filename: 'styles.[contenthash].css' }),
            new CopyWebpackPlugin({
              patterns: [{ from: 'public', to: '.' }],
            }),
          ]
        : []),
    ],
    devServer: {
      port: 3000,
      historyApiFallback: true,
      static: {
        directory: path.resolve(__dirname, 'public'),
        publicPath: '/',
      },
      proxy: [
        {
          context: ['/api'],
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      ],
    },
    devtool: isProd ? 'source-map' : 'eval-source-map',
  };
};
